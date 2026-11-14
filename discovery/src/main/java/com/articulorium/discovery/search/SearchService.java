package com.articulorium.discovery.search;

import java.time.Clock;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.index.IndexQuery;
import com.articulorium.discovery.index.IndexResult;
import com.articulorium.discovery.index.SearchIndex;
import com.articulorium.discovery.solr.SolrSyntax;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Searches the discovery layer: turns a visitor's search into a query of the search index (Solr or
 * Elasticsearch) shaped by the deployment's settings (search fields and their precedence, base
 * filters, facets, filters, sort orders), and keeps only the results the visitor may read.
 *
 * <p>Nothing a visitor sends is interpreted as query syntax beyond the search terms themselves,
 * which cannot address fields of their own: facet values are passed as values, filters and sort
 * orders are chosen by identifier among the configured ones.</p>
 */
@Service
public class SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);

    /** Longest search accepted, in characters. */
    static final int MAX_QUERY_LENGTH = 500;

    /** Deepest result reachable by paging. */
    static final int MAX_DEPTH = 10_000;

    /** Most values selected in one facet. */
    static final int MAX_FACET_VALUES = 20;

    /** Longest facet value accepted. */
    static final int MAX_VALUE_LENGTH = 500;

    /** Searched when none of the configured fields exists yet: the copy of every field. */
    static final String CATCH_ALL_FIELD = "_text_";

    private static final Pattern YEAR_RANGE = Pattern.compile("(-?\\d{1,4})-(-?\\d{1,4})");

    private final SearchIndex index;

    private final AccessVerifier access;

    private final PublicUrls urls;

    private final DiscoveryProperties properties;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param index      the search index
     * @param access     the access checks
     * @param urls       the public addresses
     * @param properties the settings
     */
    @Autowired
    public SearchService(final SearchIndex index, final AccessVerifier access, final PublicUrls urls,
            final DiscoveryProperties properties) {
        this(index, access, urls, properties, Clock.systemUTC());
    }

    /**
     * Creates the service with a clock (the current year ends year facets by default).
     *
     * @param index      the search index
     * @param access     the access checks
     * @param urls       the public addresses
     * @param properties the settings
     * @param clock      the clock
     */
    public SearchService(final SearchIndex index, final AccessVerifier access, final PublicUrls urls,
            final DiscoveryProperties properties, final Clock clock) {
        this.index = index;
        this.access = access;
        this.urls = urls;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Searches.
     *
     * @param request the search
     * @return a page of results the visitor may read, with facets
     * @throws IllegalArgumentException when the request is out of bounds (too long, too deep)
     * @throws com.articulorium.discovery.index.IndexUnavailableException when the index is unavailable
     */
    public SearchResponse search(final SearchRequest request) {
        final DiscoveryProperties.Search settings = properties.getSearch();
        final String query = request.query() == null ? "" : request.query().strip();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Searches are limited to " + MAX_QUERY_LENGTH + " characters");
        }
        final int size = request.size() <= 0 ? properties.getLayout().getResults().getPageSize()
            : Math.min(request.size(), settings.getMaxRows());
        final int page = Math.max(1, request.page());
        if ((long) page * size > MAX_DEPTH) {
            throw new IllegalArgumentException("Results beyond the first " + MAX_DEPTH + " cannot be paged to");
        }
        final Map<String, List<String>> selected = selections(request.facets());
        final Set<String> filters = new LinkedHashSet<>();
        settings.getFilters().stream().map(DiscoveryProperties.Filter::getId)
            .filter(request.filters()::contains).forEach(filters::add);
        final DiscoveryProperties.Sort sort = sort(request.sort(), query);

        IndexQuery.Text text = null;
        if (!query.isEmpty()) {
            // only fields the index can search: Solr refuses a search naming a missing field
            final List<IndexQuery.Boost> queryFields = boosts(settings.getFields(), request.locale());
            text = new IndexQuery.Text(query,
                queryFields.isEmpty() ? List.of(new IndexQuery.Boost(CATCH_ALL_FIELD, 1)) : queryFields,
                boosts(settings.getPhraseFields(), request.locale()), List.copyOf(settings.getBoostQueries()),
                StringUtils.hasText(settings.getMinimumMatch()) ? settings.getMinimumMatch() : null);
        }
        final String sortValue = query.isEmpty() && request.sort() == null ? settings.getBrowseSort()
            : sort == null ? null : sort.getValue();
        final IndexResult found = index.search(new IndexQuery(text, null, filters(request, selected, filters),
            fieldList(request.locale()), (page - 1) * size, size,
            StringUtils.hasText(sortValue) && !"score desc".equals(sortValue.trim()) ? sortValue.trim() : null,
            facets()));
        final List<JsonNode> docs = found.documents();
        final Map<String, JsonNode> byPath = new LinkedHashMap<>();
        for (final JsonNode doc : docs) {
            final String path = urls.pathOf(doc.path("id").asString(null));
            if (path != null) {
                byPath.putIfAbsent(path, doc);
            }
        }
        final List<String> readable = access.readable(byPath.keySet(), request.authorization());
        final List<SearchResponse.Result> results = readable.stream()
            .map(path -> result(path, byPath.get(path), request.locale()))
            .toList();
        final int hidden = docs.size() - results.size();
        if (hidden > 0) {
            LOG.debug("{} of {} results hidden by access control", hidden, docs.size());
        }
        return new SearchResponse(query, page, size, found.total(),
            sort == null || query.isEmpty() && request.sort() == null ? null : sort.getId(), results, hidden,
            facetResults(found, selected), List.copyOf(filters));
    }

    /**
     * The filter queries every search of the discovery layer applies.
     *
     * @return the base filters
     */
    List<String> baseFilters() {
        return properties.getSearch().getBaseFilters().stream().filter(StringUtils::hasText).toList();
    }

    private List<IndexQuery.Filter> filters(final SearchRequest request, final Map<String, List<String>> selected,
            final Set<String> filters) {
        final DiscoveryProperties.Search settings = properties.getSearch();
        final List<IndexQuery.Filter> conditions = new ArrayList<>();
        baseFilters().forEach(filter -> conditions.add(new IndexQuery.Raw(filter)));
        for (final DiscoveryProperties.Facet facet : settings.getFacets()) {
            final List<String> values = selected.get(facet.getId());
            if (values == null || values.isEmpty()) {
                continue;
            }
            final IndexQuery.Filter condition = facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE
                ? new IndexQuery.YearRanges(facet.getField(), values.stream().map(SearchService::yearRange).toList())
                : new IndexQuery.AnyOf(facet.getField(), values);
            // the facet counts its values without its own selection
            conditions.add(new IndexQuery.Tagged(facet.getId(), condition));
        }
        settings.getFilters().stream().filter(filter -> filters.contains(filter.getId()))
            .forEach(filter -> conditions.add(new IndexQuery.Raw(filter.getQuery())));
        if (StringUtils.hasText(request.within())) {
            // the members of a container are one path segment below it (platform paths are hierarchical)
            conditions.add(new IndexQuery.ChildrenOf(IndexQuery.ID_FIELD,
                urls.irisOf(ItemService.normalize(request.within()))));
        }
        // RESOURCE (anything else) cannot be selected by type: with it, no kind condition applies
        if (!request.kinds().isEmpty() && !request.kinds().contains(ResourceKind.RESOURCE)) {
            final List<String> types = new ArrayList<>();
            // a fixed order keeps the index query, and so its caches, stable
            for (final ResourceKind kind : ResourceKind.values()) {
                if (request.kinds().contains(kind)) {
                    types.addAll(kind == ResourceKind.COLLECTION
                        ? List.of(Articulorium.COLLECTION, Pcdm.COLLECTION) : List.of(kind.type()));
                }
            }
            conditions.add(new IndexQuery.AnyOf(settings.getTypeField(), types));
        }
        return conditions;
    }

    private List<IndexQuery.Facet> facets() {
        final List<IndexQuery.Facet> facets = new ArrayList<>();
        for (final DiscoveryProperties.Facet facet : properties.getSearch().getFacets()) {
            facets.add(facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE
                ? new IndexQuery.Years(facet.getId(), facet.getField(), facet.getStart(), endYear(facet), facet.getGap())
                : new IndexQuery.Terms(facet.getId(), facet.getField(), facet.getLimit(),
                    facet.getSort() == DiscoveryProperties.FacetSort.INDEX));
        }
        return facets;
    }

    private List<SearchResponse.FacetResult> facetResults(final IndexResult found,
            final Map<String, List<String>> selected) {
        final List<SearchResponse.FacetResult> results = new ArrayList<>();
        for (final DiscoveryProperties.Facet facet : properties.getSearch().getFacets()) {
            final List<String> chosen = selected.getOrDefault(facet.getId(), List.of());
            final List<SearchResponse.FacetValue> values = new ArrayList<>();
            final Set<String> seen = new LinkedHashSet<>();
            for (final IndexResult.Count count : found.facet(facet.getId())) {
                if (facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE) {
                    final String value = rangeLabel(count.value(), facet);
                    if (count.count() > 0 && value != null && seen.add(value)) {
                        values.add(new SearchResponse.FacetValue(value, count.count(), chosen.contains(value)));
                    }
                } else if (seen.add(count.value())) {
                    values.add(new SearchResponse.FacetValue(count.value(), count.count(),
                        chosen.contains(count.value())));
                }
            }
            // a selected value outside the listed ones stays visible, so it can be removed
            chosen.stream().filter(value -> !seen.contains(value))
                .forEach(value -> values.add(new SearchResponse.FacetValue(value, 0, true)));
            results.add(new SearchResponse.FacetResult(facet.getId(), values));
        }
        return results;
    }

    private SearchResponse.Result result(final String path, final JsonNode doc, @Nullable final String locale) {
        final DiscoveryProperties.Search settings = properties.getSearch();
        final List<String> types = SolrDocuments.values(doc, "type");
        final String title = SolrDocuments.first(doc, settings.getTitleField(), locale);
        final String description = SolrDocuments.first(doc, settings.getDescriptionField(), locale);
        final String date = StringUtils.hasText(settings.getDateField())
            ? SolrDocuments.first(doc, settings.getDateField(), null) : null;
        final String thumbnail = StringUtils.hasText(settings.getThumbnailField())
            ? SolrDocuments.first(doc, settings.getThumbnailField(), null) : null;
        return new SearchResponse.Result(path, title == null ? SolrDocuments.fallbackTitle(path) : title,
            truncate(description), ResourceKind.of(types), types, date, safeLink(thumbnail));
    }

    private List<String> fieldList(@Nullable final String locale) {
        final DiscoveryProperties.Search settings = properties.getSearch();
        final Set<String> fields = new LinkedHashSet<>(List.of("id", "type"));
        fields.addAll(SolrDocuments.fieldList(settings.getTitleField(), locale));
        fields.addAll(SolrDocuments.fieldList(settings.getDescriptionField(), locale));
        if (StringUtils.hasText(settings.getDateField())) {
            fields.add(settings.getDateField());
        }
        if (StringUtils.hasText(settings.getThumbnailField())) {
            fields.add(settings.getThumbnailField());
        }
        return List.copyOf(fields);
    }

    private Map<String, List<String>> selections(final Map<String, List<String>> requested) {
        final Map<String, List<String>> selected = new LinkedHashMap<>();
        for (final DiscoveryProperties.Facet facet : properties.getSearch().getFacets()) {
            final List<String> values = requested.getOrDefault(facet.getId(), List.of()).stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
            if (values.size() > MAX_FACET_VALUES) {
                throw new IllegalArgumentException("At most " + MAX_FACET_VALUES + " values of a facet can be selected");
            }
            for (final String value : values) {
                if (value.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("Facet values are limited to " + MAX_VALUE_LENGTH + " characters");
                }
                if (facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE && !YEAR_RANGE.matcher(value).matches()) {
                    throw new IllegalArgumentException("Not a year range: " + value);
                }
            }
            if (!values.isEmpty()) {
                selected.put(facet.getId(), values);
            }
        }
        return selected;
    }

    private DiscoveryProperties.@Nullable Sort sort(@Nullable final String id, final String query) {
        final List<DiscoveryProperties.Sort> sorts = properties.getSearch().getSorts();
        if (id != null) {
            return sorts.stream().filter(sort -> sort.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown sort order: " + id));
        }
        return sorts.isEmpty() || query.isEmpty() ? null : sorts.get(0);
    }

    private List<IndexQuery.Boost> boosts(final List<DiscoveryProperties.SearchField> fields,
            @Nullable final String locale) {
        final List<IndexQuery.Boost> boosts = new ArrayList<>();
        for (final DiscoveryProperties.SearchField field : fields) {
            if (index.hasField(field.getField())) {
                boosts.add(new IndexQuery.Boost(field.getField(), field.getBoost()));
            }
            if (field.isLocalized()) {
                SolrSyntax.languageSuffix(locale).map(suffix -> field.getField() + "_txt_" + suffix)
                    .filter(index::hasField)
                    .ifPresent(localized -> boosts.add(new IndexQuery.Boost(localized, field.getBoost())));
            }
        }
        return boosts;
    }

    private int endYear(final DiscoveryProperties.Facet facet) {
        return facet.getEnd() > 0 ? facet.getEnd() : Year.now(clock.withZone(ZoneOffset.UTC)).getValue() + 1;
    }

    private @Nullable String rangeLabel(final String start, final DiscoveryProperties.Facet facet) {
        if (start.length() < 4) {
            return null;
        }
        try {
            final int from = Integer.parseInt(start.substring(0, start.indexOf('-', 1) > 0 ? start.indexOf('-', 1) : 4));
            final int to = Math.min(from + facet.getGap(), endYear(facet)) - 1;
            return from + "-" + to;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static IndexQuery.YearRange yearRange(final String value) {
        final Matcher matcher = YEAR_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a year range: " + value);
        }
        final int from = Integer.parseInt(matcher.group(1));
        final int to = Integer.parseInt(matcher.group(2));
        if (to < from) {
            throw new IllegalArgumentException("Not a year range: " + value);
        }
        return new IndexQuery.YearRange(from, to);
    }

    private @Nullable String truncate(@Nullable final String text) {
        if (text == null) {
            return null;
        }
        final int limit = Math.max(1, properties.getLayout().getResults().getDescriptionLength());
        if (text.length() <= limit) {
            return text;
        }
        final int space = text.lastIndexOf(' ', limit);
        return text.substring(0, space > limit / 2 ? space : limit).stripTrailing() + "…";
    }

    /**
     * Keeps a link only when it is an http(s) URL.
     *
     * @param link the link from the index
     * @return the link, {@code null} for other schemes
     */
    static @Nullable String safeLink(@Nullable final String link) {
        if (link == null) {
            return null;
        }
        final String lower = link.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? link.trim() : null;
    }

}
