package com.articulorium.discovery.elasticsearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.discovery.index.IndexQuery;

import org.springframework.util.StringUtils;

/**
 * Renders {@link IndexQuery}s as bodies of Elasticsearch's search API (Query DSL).
 *
 * <p>Values from visitors only ever become JSON values of term-level queries or the text of a
 * {@code simple_query_string} query, which cannot address fields of its own and never fails on
 * syntax. Queries from the settings are written in Lucene query syntax and run as
 * {@code query_string} queries, so the same settings work for Solr and Elasticsearch.</p>
 */
final class ElasticsearchRequests {

    /** Name of the aggregation holding a facet's counts, inside the facet's filter aggregation. */
    static final String VALUES = "values";

    /** How Lucene queries name relevance in a sort order. */
    private static final String SCORE = "score";

    private ElasticsearchRequests() {
    }

    /**
     * The body of a search.
     *
     * @param query the query
     * @return the body of {@code POST /<index>/_search}
     */
    static Map<String, Object> search(final IndexQuery query) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", query.start());
        body.put("size", query.rows());
        body.put("track_total_hits", true);
        if (!query.fields().contains("*")) {
            body.put("_source", query.fields());
        }
        // filters of facets apply after the facets are counted, so each facet can ignore its own
        final List<IndexQuery.Tagged> tagged = new ArrayList<>();
        final List<Object> filters = new ArrayList<>();
        for (final IndexQuery.Filter filter : query.filters()) {
            if (filter instanceof IndexQuery.Tagged facetFilter) {
                tagged.add(facetFilter);
            } else {
                filters.add(filter(filter));
            }
        }
        body.put("query", query(query, filters));
        if (!tagged.isEmpty()) {
            body.put("post_filter", all(tagged.stream().map(ElasticsearchRequests::filter).toList()));
        }
        final List<Object> sort = sort(query.sort());
        if (!sort.isEmpty()) {
            body.put("sort", sort);
        }
        final Map<String, Object> aggregations = new LinkedHashMap<>();
        for (final IndexQuery.Facet facet : query.facets()) {
            final Map<String, Object> values = values(facet);
            if (values != null) {
                final Map<String, Object> aggregation = new LinkedHashMap<>();
                aggregation.put("filter", all(tagged.stream().filter(other -> !other.tag().equals(facet.id()))
                    .map(ElasticsearchRequests::filter).toList()));
                aggregation.put("aggs", Map.of(VALUES, values));
                aggregations.put(facet.id(), aggregation);
            }
        }
        if (!aggregations.isEmpty()) {
            body.put("aggs", aggregations);
        }
        return body;
    }

    private static Map<String, Object> query(final IndexQuery query, final List<Object> filters) {
        final Map<String, Object> bool = new LinkedHashMap<>();
        final IndexQuery.Text text = query.text();
        if (text != null) {
            final Map<String, Object> terms = new LinkedHashMap<>();
            terms.put("query", text.query());
            terms.put("fields", text.fields().stream().map(IndexQuery.Boost::term).toList());
            terms.put("default_operator", "or");
            if (StringUtils.hasText(text.minimumMatch())) {
                terms.put("minimum_should_match", text.minimumMatch());
            }
            bool.put("must", List.of(Map.of("simple_query_string", terms)));
            // matches of these only rank higher: documents need not match them
            final List<Object> boosts = new ArrayList<>();
            if (!text.phraseFields().isEmpty()) {
                final Map<String, Object> phrase = new LinkedHashMap<>();
                phrase.put("query", text.query());
                phrase.put("type", "phrase");
                phrase.put("fields", text.phraseFields().stream().map(IndexQuery.Boost::term).toList());
                boosts.add(Map.of("multi_match", phrase));
            }
            text.boostQueries().forEach(boost -> boosts.add(lucene(boost)));
            if (!boosts.isEmpty()) {
                bool.put("should", boosts);
            }
        } else if (query.match() != null) {
            filters.addFirst(filter(query.match()));
        }
        if (!filters.isEmpty()) {
            bool.put("filter", filters);
        }
        return Map.of("bool", bool);
    }

    private static Map<String, Object> filter(final IndexQuery.Filter filter) {
        return switch (filter) {
            case IndexQuery.Raw raw -> lucene(raw.query());
            case IndexQuery.AnyOf anyOf -> Map.of("terms", Map.of(anyOf.field(), anyOf.values()));
            case IndexQuery.YearRanges years -> any(years.ranges().stream()
                .map(span -> range(years.field(), IndexQuery.startOfYear(span.from()),
                    IndexQuery.startOfYear(span.to() + 1)))
                .toList());
            case IndexQuery.ChildrenOf children -> any(children.parents().stream()
                .map(parent -> Map.<String, Object>of("regexp", Map.of(children.field(),
                    Map.of("value", regex(parent.endsWith("/") ? parent : parent + "/") + "[^/]+"))))
                .toList());
            case IndexQuery.Tagged tagged -> filter(tagged.filter());
        };
    }

    /** A query from the settings. */
    private static Map<String, Object> lucene(final String query) {
        return Map.of("query_string", Map.of("query", query));
    }

    private static Map<String, Object> range(final String field, final String from, final String to) {
        final Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("gte", from);
        bounds.put("lt", to);
        return Map.of("range", Map.of(field, bounds));
    }

    private static Map<String, Object> any(final List<Map<String, Object>> queries) {
        return queries.size() == 1 ? queries.getFirst()
            : Map.of("bool", Map.of("should", queries, "minimum_should_match", 1));
    }

    private static Map<String, Object> all(final List<Map<String, Object>> queries) {
        return queries.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("filter", queries));
    }

    /**
     * The sort of a search.
     *
     * @param order {@code <field> asc|desc}, several separated by commas; {@code null} for relevance
     * @return the sort with the identifier as its last criterion, empty for relevance. Sorting on
     *     a field the index does not have yet is not an error ({@code unmapped_type})
     */
    static List<Object> sort(final String order) {
        final List<Object> sort = new ArrayList<>();
        if (!StringUtils.hasText(order)) {
            return sort;
        }
        for (final String criterion : (order + ", " + IndexQuery.ID_FIELD + " asc").split(",")) {
            final String[] parts = criterion.trim().split("\\s+");
            final String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1]) ? "desc" : "asc";
            if (SCORE.equals(parts[0])) {
                sort.add(Map.of("_score", Map.of("order", direction)));
            } else {
                final Map<String, Object> options = new LinkedHashMap<>();
                options.put("order", direction);
                options.put("unmapped_type", "keyword");
                sort.add(Map.of(parts[0], options));
            }
        }
        return sort;
    }

    private static Map<String, Object> values(final IndexQuery.Facet facet) {
        return switch (facet) {
            case IndexQuery.Terms terms -> {
                final Map<String, Object> aggregation = new LinkedHashMap<>();
                aggregation.put("field", terms.field());
                aggregation.put("size", Math.max(1, terms.limit()));
                aggregation.put("min_doc_count", 1);
                aggregation.put("order", terms.sortByValue() ? Map.of("_key", "asc") : Map.of("_count", "desc"));
                yield Map.of("terms", aggregation);
            }
            case IndexQuery.Years years -> {
                if (years.gap() <= 0 || years.end() <= years.start()) {
                    yield null;
                }
                // one range per gap, keyed by its start like Solr's range facets; the last one
                // ends with the facet
                final List<Object> ranges = new ArrayList<>();
                for (int year = years.start(); year < years.end(); year += years.gap()) {
                    final Map<String, Object> range = new LinkedHashMap<>();
                    range.put("key", IndexQuery.startOfYear(year));
                    range.put("from", IndexQuery.startOfYear(year));
                    range.put("to", IndexQuery.startOfYear(Math.min(year + years.gap(), years.end())));
                    ranges.add(range);
                }
                final Map<String, Object> aggregation = new LinkedHashMap<>();
                aggregation.put("field", years.field());
                aggregation.put("ranges", ranges);
                yield Map.of("date_range", aggregation);
            }
        };
    }

    /**
     * Escapes a value for a Lucene regular expression: every character that is not a letter or a
     * digit is escaped.
     *
     * @param value the literal value
     * @return the escaped value
     */
    static String regex(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() * 2);
        value.codePoints().forEach(c -> {
            if (!Character.isLetterOrDigit(c)) {
                escaped.append('\\');
            }
            escaped.appendCodePoint(c);
        });
        return escaped.toString();
    }

}
