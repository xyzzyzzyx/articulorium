package com.articulorium.discovery.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.iiif.IiifSummary;
import com.articulorium.discovery.iiif.IiifSummaryClient;
import com.articulorium.discovery.index.IndexQuery;
import com.articulorium.discovery.index.SearchIndex;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Describes single resources of the discovery layer for their pages: title, description and
 * configured metadata in the visitor's language, the trail of parent communities and collections,
 * and a summary of the IIIF export the viewer presents.
 *
 * <p>A resource has a page when the visitor may read it, it is in the index and it matches the
 * discovery layer's base filters; otherwise it is reported as not found, whatever the reason.</p>
 */
@Service
public class ItemService {

    /** Most ancestors listed in the trail. */
    static final int MAX_DEPTH = 10;

    /** Longest path accepted. */
    static final int MAX_PATH_LENGTH = 1024;

    private final SearchIndex index;

    private final AccessVerifier access;

    private final IiifSummaryClient iiif;

    private final PublicUrls urls;

    private final DiscoveryProperties properties;

    private final SearchService search;

    /**
     * Creates the service.
     *
     * @param index      the search index
     * @param access     the access checks
     * @param iiif       the IIIF summaries
     * @param urls       the public addresses
     * @param properties the settings
     * @param search     the search (its base filters)
     */
    public ItemService(final SearchIndex index, final AccessVerifier access, final IiifSummaryClient iiif,
            final PublicUrls urls, final DiscoveryProperties properties, final SearchService search) {
        this.index = index;
        this.access = access;
        this.iiif = iiif;
        this.urls = urls;
        this.properties = properties;
        this.search = search;
    }

    /**
     * Describes a resource.
     *
     * @param requestedPath the resource path
     * @param locale        the visitor's language tag, may be {@code null}
     * @param authorization the visitor's {@code Authorization} header, {@code null} when anonymous
     * @return the description
     * @throws NotFoundException when the resource has no page for the visitor
     * @throws IllegalArgumentException when the path is malformed
     */
    public ItemView item(final String requestedPath, @Nullable final String locale, @Nullable final String authorization) {
        final String path = normalize(requestedPath);
        if (path.isEmpty() || !access.mayRead(path, authorization)) {
            throw new NotFoundException(path);
        }
        final JsonNode doc = document(path).orElseThrow(() -> new NotFoundException(path));
        final DiscoveryProperties.Search settings = properties.getSearch();
        final List<String> types = SolrDocuments.values(doc, "type");
        final ResourceKind kind = ResourceKind.of(types);
        final String title = SolrDocuments.first(doc, settings.getTitleField(), locale);
        final List<ItemView.FieldValues> fields = new ArrayList<>();
        for (final DiscoveryProperties.Field field : properties.getLayout().getItem().getFields()) {
            final List<String> values = SolrDocuments.localized(doc, field.getField(), locale).stream()
                .map(value -> field.isLink() ? SearchService.safeLink(value) : value)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
            if (!values.isEmpty()) {
                fields.add(new ItemView.FieldValues(field.getField(), values, field.isLink()));
            }
        }
        final IiifSummary summary = kind == ResourceKind.RESOURCE ? null
            : iiif.summary(path, authorization).orElse(null);
        final String thumbnail = summary != null && summary.thumbnail() != null ? summary.thumbnail()
            : StringUtils.hasText(settings.getThumbnailField())
                ? SearchService.safeLink(SolrDocuments.first(doc, settings.getThumbnailField(), null)) : null;
        return new ItemView(path, urls.iriOf(path), title == null ? SolrDocuments.fallbackTitle(path) : title,
            SolrDocuments.first(doc, settings.getDescriptionField(), locale), kind, types,
            SolrDocuments.first(doc, "created", null), SolrDocuments.first(doc, settings.getModifiedField(), null),
            StringUtils.hasText(settings.getDateField()) ? SolrDocuments.first(doc, settings.getDateField(), null) : null,
            SolrDocuments.values(doc, "languages"), fields, trail(path, locale, authorization), summary, thumbnail,
            properties.getLayout().getItem().isRepositoryLink() ? urls.repositoryUiUrl(path) : null);
    }

    /**
     * Normalizes a requested path.
     *
     * @param path the path, with or without leading and trailing {@code /}
     * @return the path without them
     * @throws IllegalArgumentException for paths with empty, {@code .} or {@code ..} segments,
     *     control characters, or longer than {@value #MAX_PATH_LENGTH} characters
     */
    static String normalize(final String path) {
        if (path == null) {
            return "";
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Paths are limited to " + MAX_PATH_LENGTH + " characters");
        }
        String normalized = path.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return normalized;
        }
        for (final String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Not a resource path: " + path);
            }
        }
        if (normalized.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '?' || c == '#')) {
            throw new IllegalArgumentException("Not a resource path: " + path);
        }
        return normalized;
    }

    private Optional<JsonNode> document(final String path) {
        // the platform may have identified the resource by its public or its internal address
        final List<JsonNode> docs = index.search(new IndexQuery(null,
            new IndexQuery.AnyOf(IndexQuery.ID_FIELD, urls.irisOf(path)), baseFilters(), List.of("*"), 0, 1, null,
            List.of())).documents();
        return docs.isEmpty() ? Optional.empty() : Optional.of(docs.getFirst());
    }

    private List<IndexQuery.Filter> baseFilters() {
        return search.baseFilters().stream().<IndexQuery.Filter>map(IndexQuery.Raw::new).toList();
    }

    /**
     * The trail of a resource: its ancestors (platform paths are hierarchical) that are part of the
     * discovery layer and that the visitor may read, outermost first. Ancestors outside the
     * discovery layer (the community root, {@code members} containers) are skipped.
     */
    private List<ItemView.Crumb> trail(final String path, @Nullable final String locale,
            @Nullable final String authorization) {
        final List<String> ancestors = new ArrayList<>();
        for (int slash = path.lastIndexOf('/'); slash > 0 && ancestors.size() < MAX_DEPTH;
                slash = path.lastIndexOf('/', slash - 1)) {
            ancestors.addFirst(path.substring(0, slash));
        }
        if (ancestors.isEmpty()) {
            return List.of();
        }
        final List<String> iris = new ArrayList<>();
        ancestors.forEach(ancestor -> iris.addAll(urls.irisOf(ancestor)));
        final Map<String, JsonNode> found = new HashMap<>();
        for (final JsonNode doc : index.search(new IndexQuery(null, new IndexQuery.AnyOf(IndexQuery.ID_FIELD, iris),
                baseFilters(), trailFields(locale), 0, iris.size(), null, List.of())).documents()) {
            final String ancestor = urls.pathOf(doc.path("id").asString(null));
            if (ancestor != null) {
                found.putIfAbsent(ancestor, doc);
            }
        }
        final List<ItemView.Crumb> trail = new ArrayList<>();
        final List<String> readable = access.readable(ancestors.stream().filter(found::containsKey).toList(),
            authorization);
        for (final String ancestor : readable) {
            final JsonNode doc = found.get(ancestor);
            final String title = SolrDocuments.first(doc, properties.getSearch().getTitleField(), locale);
            trail.add(new ItemView.Crumb(ancestor, title == null ? SolrDocuments.fallbackTitle(ancestor) : title,
                ResourceKind.of(SolrDocuments.values(doc, "type"))));
        }
        return trail;
    }

    private List<String> trailFields(@Nullable final String locale) {
        final List<String> fields = new ArrayList<>(List.of("id", "type"));
        fields.addAll(SolrDocuments.fieldList(properties.getSearch().getTitleField(), locale));
        return fields;
    }

    /**
     * A resource, as its page presents it.
     *
     * @param path          its path
     * @param iri           its public IRI
     * @param title         its title in the visitor's language when it has one
     * @param description   its description, {@code null} when it has none
     * @param kind          what it is
     * @param types         its RDF types
     * @param created       when it was created
     * @param modified      when it was last modified
     * @param date          its descriptive date, {@code null} when it has none
     * @param languages     languages of its texts
     * @param fields        the configured metadata fields that have values, in order
     * @param trail         its parent communities and collections, outermost first
     * @param iiif          a summary of its IIIF export, {@code null} when it has none
     * @param thumbnail     its preview image, {@code null} when it has none
     * @param repositoryUrl its page in the repository's user interface, when enabled
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemView(String path, String iri, String title, @Nullable String description, ResourceKind kind,
            List<String> types, @Nullable String created, @Nullable String modified, @Nullable String date,
            List<String> languages, List<FieldValues> fields, List<Crumb> trail, @Nullable IiifSummary iiif,
            @Nullable String thumbnail, @Nullable String repositoryUrl) {

        /**
         * The values of a metadata field.
         *
         * @param field  the field of the search index
         * @param values its values
         * @param link   whether the values are links
         */
        public record FieldValues(String field, List<String> values, boolean link) {
        }

        /**
         * A parent in the trail.
         *
         * @param path  its path
         * @param title its title
         * @param kind  what it is
         */
        public record Crumb(String path, String title, ResourceKind kind) {
        }

    }

}
