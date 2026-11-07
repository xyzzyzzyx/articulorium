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
import java.util.stream.Collectors;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.solr.SolrSearchClient;
import com.articulorium.discovery.solr.SolrSyntax;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Searches the discovery layer: turns a visitor's search into a Solr query shaped by the
 * deployment's settings (search fields and their precedence, base filters, facets, filters, sort
 * orders), and keeps only the results the visitor may read.
 *
 * <p>Nothing a visitor sends is interpreted as Solr syntax beyond the search terms themselves,
 * which the {@code edismax} parser reads with field queries turned off ({@code uf=-*}): facet
 * values are quoted, filters and sort orders are chosen by identifier among the configured ones.</p>
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

    private static final Pattern YEAR_RANGE = Pattern.compile("(-?\\d{1,4})-(-?\\d{1,4})");

    private final SolrSearchClient solr;

    private final AccessVerifier access;

    private final PublicUrls urls;

    private final DiscoveryProperties properties;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param solr       the Solr client
     * @param access     the access checks
     * @param urls       the public addresses
     * @param properties the settings
     */
    @Autowired
    public SearchService(final SolrSearchClient solr, final AccessVerifier access, final PublicUrls urls,
            final DiscoveryProperties properties) {
        this(solr, access, urls, properties, Clock.systemUTC());
    }

    /**
     * Creates the service with a clock (the current year ends year facets by default).
     *
     * @param solr       the Solr client
     * @param access     the access checks
     * @param urls       the public addresses
     * @param properties the settings
     * @param clock      the clock
     */
    public SearchService(final SolrSearchClient solr, final AccessVerifier access, final PublicUrls urls,
            final DiscoveryProperties properties, final Clock clock) {
        this.solr = solr;
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
     * @throws com.articulorium.discovery.solr.SolrUnavailableException when the index is unavailable
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

        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (query.isEmpty()) {
            params.add("q", "*:*");
        } else {
            params.add("defType", "edismax");
            params.add("q", query);
            params.add("qf", boosts(settings.getFields(), request.locale()));
            if (!settings.getPhraseFields().isEmpty()) {
                params.add("pf", boosts(settings.getPhraseFields(), request.locale()));
            }
            settings.getBoostQueries().forEach(boost -> params.add("bq", boost));
            if (StringUtils.hasText(settings.getMinimumMatch())) {
                params.add("mm", settings.getMinimumMatch());
            }
            // the search terms may not address fields of their own (access control, internal fields)
            params.add("uf", "-*");
        }
        filters(params, request, selected, filters);
        params.add("fl", String.join(",", fieldList(request.locale())));
        params.add("start", Integer.toString((page - 1) * size));
        params.add("rows", Integer.toString(size));
        final String sortValue = query.isEmpty() && request.sort() == null ? settings.getBrowseSort()
            : sort == null ? null : sort.getValue();
        if (StringUtils.hasText(sortValue) && !"score desc".equals(sortValue.trim())) {
            params.add("sort", sortValue.trim() + ", id asc");
        }
        facets(params);

        final JsonNode response = solr.select(params);
        final JsonNode docs = response.path("response").path("docs");
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
        return new SearchResponse(query, page, size, response.path("response").path("numFound").asLong(0),
            sort == null || query.isEmpty() && request.sort() == null ? null : sort.getId(), results, hidden,
            facetResults(response.path("facet_counts"), selected), List.copyOf(filters));
    }

    /**
     * The filter queries every search of the discovery layer applies.
     *
     * @return the base filters
     */
    List<String> baseFilters() {
        return properties.getSearch().getBaseFilters().stream().filter(StringUtils::hasText).toList();
    }

    private void filters(final MultiValueMap<String, String> params, final SearchRequest request,
            final Map<String, List<String>> selected, final Set<String> filters) {
        final DiscoveryProperties.Search settings = properties.getSearch();
        baseFilters().forEach(filter -> params.add("fq", filter));
        for (final DiscoveryProperties.Facet facet : settings.getFacets()) {
            final List<String> values = selected.get(facet.getId());
            if (values == null || values.isEmpty()) {
                continue;
            }
            final String query = facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE
                ? values.stream().map(value -> yearRange(facet.getField(), value))
                    .collect(Collectors.joining(" OR ", "(", ")"))
                : SolrSyntax.anyOf(facet.getField(), values);
            params.add("fq", SolrSyntax.tagged(facet.getId(), query));
        }
        settings.getFilters().stream().filter(filter -> filters.contains(filter.getId()))
            .forEach(filter -> params.add("fq", filter.getQuery()));
        if (StringUtils.hasText(request.within())) {
            params.add("fq", SolrSyntax.term(settings.getParentField(), urls.iriOf(ItemService.normalize(request.within()))));
        }
        if (request.kind() != null && request.kind() != ResourceKind.RESOURCE) {
            final List<String> types = request.kind() == ResourceKind.COLLECTION
                ? List.of(Articulorium.COLLECTION, Pcdm.COLLECTION) : List.of(request.kind().type());
            params.add("fq", SolrSyntax.anyOf(settings.getTypeField(), types));
        }
    }

    private void facets(final MultiValueMap<String, String> params) {
        final List<DiscoveryProperties.Facet> facets = properties.getSearch().getFacets();
        if (facets.isEmpty()) {
            return;
        }
        params.add("facet", "true");
        params.add("facet.mincount", "1");
        for (final DiscoveryProperties.Facet facet : facets) {
            final String field = facet.getField();
            final String local = "{!ex=" + facet.getId() + " key=" + facet.getId() + "}" + field;
            if (facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE) {
                params.add("facet.range", local);
                params.add("f." + field + ".facet.range.start", facet.getStart() + "-01-01T00:00:00Z");
                params.add("f." + field + ".facet.range.end", endYear(facet) + "-01-01T00:00:00Z");
                params.add("f." + field + ".facet.range.gap", "+" + facet.getGap() + "YEARS");
                params.add("f." + field + ".facet.range.hardend", "true");
            } else {
                params.add("facet.field", local);
                params.add("f." + field + ".facet.limit", Integer.toString(facet.getLimit()));
                params.add("f." + field + ".facet.sort", facet.getSort() == DiscoveryProperties.FacetSort.INDEX
                    ? "index" : "count");
            }
        }
    }

    private List<SearchResponse.FacetResult> facetResults(final JsonNode counts,
            final Map<String, List<String>> selected) {
        final List<SearchResponse.FacetResult> results = new ArrayList<>();
        for (final DiscoveryProperties.Facet facet : properties.getSearch().getFacets()) {
            final List<String> chosen = selected.getOrDefault(facet.getId(), List.of());
            final List<SearchResponse.FacetValue> values = new ArrayList<>();
            final Set<String> seen = new LinkedHashSet<>();
            if (facet.getType() == DiscoveryProperties.FacetType.YEAR_RANGE) {
                final JsonNode list = counts.path("facet_ranges").path(facet.getId()).path("counts");
                for (int i = 0; i + 1 < list.size(); i += 2) {
                    final long count = list.get(i + 1).asLong(0);
                    final String value = rangeLabel(list.get(i).asString(""), facet);
                    if (count > 0 && value != null && seen.add(value)) {
                        values.add(new SearchResponse.FacetValue(value, count, chosen.contains(value)));
                    }
                }
            } else {
                final JsonNode list = counts.path("facet_fields").path(facet.getId());
                for (int i = 0; i + 1 < list.size(); i += 2) {
                    final String value = list.get(i).asString("");
                    if (seen.add(value)) {
                        values.add(new SearchResponse.FacetValue(value, list.get(i + 1).asLong(0), chosen.contains(value)));
                    }
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

    private static String boosts(final List<DiscoveryProperties.SearchField> fields, @Nullable final String locale) {
        final List<String> terms = new ArrayList<>();
        for (final DiscoveryProperties.SearchField field : fields) {
            final String boost = boost(field.getBoost());
            terms.add(field.getField() + boost);
            if (field.isLocalized()) {
                SolrSyntax.languageSuffix(locale).ifPresent(suffix -> terms.add(field.getField() + "_txt_" + suffix + boost));
            }
        }
        return String.join(" ", terms);
    }

    private static String boost(final double boost) {
        return boost == 1 ? "" : "^" + (boost == Math.rint(boost) ? Long.toString((long) boost)
            : String.format(Locale.ROOT, "%s", boost));
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

    private static String yearRange(final String field, final String value) {
        final Matcher matcher = YEAR_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a year range: " + value);
        }
        final int from = Integer.parseInt(matcher.group(1));
        final int to = Integer.parseInt(matcher.group(2));
        if (to < from) {
            throw new IllegalArgumentException("Not a year range: " + value);
        }
        return field + ":[" + year(from) + " TO " + year(to + 1) + "}";
    }

    private static String year(final int year) {
        return String.format(Locale.ROOT, "%04d-01-01T00:00:00Z", year);
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
