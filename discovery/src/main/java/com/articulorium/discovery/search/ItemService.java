package com.articulorium.discovery.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.iiif.IiifSummary;
import com.articulorium.discovery.iiif.IiifSummaryClient;
import com.articulorium.discovery.solr.SolrSearchClient;
import com.articulorium.discovery.solr.SolrSyntax;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

    /** Most parents followed for the trail. */
    static final int MAX_DEPTH = 10;

    /** Longest path accepted. */
    static final int MAX_PATH_LENGTH = 1024;

    private final SolrSearchClient solr;

    private final AccessVerifier access;

    private final IiifSummaryClient iiif;

    private final PublicUrls urls;

    private final DiscoveryProperties properties;

    private final SearchService search;

    /**
     * Creates the service.
     *
     * @param solr       the Solr client
     * @param access     the access checks
     * @param iiif       the IIIF summaries
     * @param urls       the public addresses
     * @param properties the settings
     * @param search     the search (its base filters)
     */
    public ItemService(final SolrSearchClient solr, final AccessVerifier access, final IiifSummaryClient iiif,
            final PublicUrls urls, final DiscoveryProperties properties, final SearchService search) {
        this.solr = solr;
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
            SolrDocuments.values(doc, "languages"), fields, trail(doc, locale, authorization), summary, thumbnail,
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
        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", SolrSyntax.term("id", urls.iriOf(path)));
        search.baseFilters().forEach(filter -> params.add("fq", filter));
        params.add("rows", "1");
        params.add("fl", "*");
        final JsonNode docs = solr.select(params).path("response").path("docs");
        return docs.isArray() && !docs.isEmpty() ? Optional.of(docs.get(0)) : Optional.empty();
    }

    private List<ItemView.Crumb> trail(final JsonNode doc, @Nullable final String locale,
            @Nullable final String authorization) {
        final List<ItemView.Crumb> trail = new ArrayList<>();
        String parent = SolrDocuments.first(doc, "hasParent", null);
        for (int depth = 0; depth < MAX_DEPTH && parent != null; depth++) {
            final String path = urls.pathOf(parent);
            if (path == null || path.isEmpty() || !access.mayRead(path, authorization)) {
                break;
            }
            final Optional<JsonNode> found = document(path);
            if (found.isEmpty()) {
                break;
            }
            final String title = SolrDocuments.first(found.get(), properties.getSearch().getTitleField(), locale);
            trail.addFirst(new ItemView.Crumb(path, title == null ? SolrDocuments.fallbackTitle(path) : title,
                ResourceKind.of(SolrDocuments.values(found.get(), "type"))));
            parent = SolrDocuments.first(found.get(), "hasParent", null);
        }
        return trail;
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
         * @param field  the Solr field
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
