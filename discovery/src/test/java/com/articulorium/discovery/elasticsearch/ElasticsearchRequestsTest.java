package com.articulorium.discovery.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.articulorium.discovery.index.IndexQuery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how queries are rendered in Elasticsearch's Query DSL.
 */
class ElasticsearchRequestsTest {

    private static JsonNode body(final IndexQuery query) {
        return JsonMapper.builder().build().valueToTree(ElasticsearchRequests.search(query));
    }

    private static IndexQuery query(final IndexQuery.Filter match, final List<IndexQuery.Filter> filters,
            final String sort, final List<IndexQuery.Facet> facets) {
        return new IndexQuery(null, match, filters, List.of("id"), 0, 10, sort, facets);
    }

    @Test
    void matchesEverythingWithoutTermsOrFilters() {
        final JsonNode body = body(query(null, List.of(), null, List.of()));

        assertThat(body.path("query").path("bool").isEmpty()).isTrue();
        assertThat(body.has("post_filter")).isFalse();
        assertThat(body.has("sort")).isFalse();
        assertThat(body.has("aggs")).isFalse();
        assertThat(body.path("track_total_hits").asBoolean()).isTrue();
    }

    @Test
    void settingsQueriesRunAsLuceneQueries() {
        final JsonNode body = body(query(null, List.of(new IndexQuery.Raw("-id:*\\/members\\/*")), null, List.of()));

        assertThat(body.path("query").path("bool").path("filter").get(0).path("query_string").path("query").asString())
            .isEqualTo("-id:*\\/members\\/*");
    }

    @Test
    void visitorsValuesAreNeverQuerySyntax() {
        final JsonNode body = body(query(new IndexQuery.AnyOf("id", List.of("http://x/a\" OR *:*")), List.of(), null,
            List.of()));

        assertThat(body.path("query").path("bool").path("filter").get(0).path("terms").path("id").get(0).asString())
            .isEqualTo("http://x/a\" OR *:*");
    }

    @Test
    void childrenAreOneSegmentBelowTheirParent() {
        final JsonNode filter = body(query(null,
            List.of(new IndexQuery.ChildrenOf("id", List.of("http://x.org/c", "http://y/c/"))), null, List.of()))
            .path("query").path("bool").path("filter").get(0);

        final JsonNode alternatives = filter.path("bool").path("should");
        assertThat(filter.path("bool").path("minimum_should_match").asInt()).isEqualTo(1);
        assertThat(alternatives.get(0).path("regexp").path("id").path("value").asString())
            .isEqualTo("http\\:\\/\\/x\\.org\\/c\\/[^/]+");
        assertThat(alternatives.get(1).path("regexp").path("id").path("value").asString())
            .isEqualTo("http\\:\\/\\/y\\/c\\/[^/]+");
    }

    @Test
    void aSingleAlternativeNeedsNoBooleanQuery() {
        final JsonNode filter = body(query(null,
            List.of(new IndexQuery.YearRanges("date", List.of(new IndexQuery.YearRange(1850, 1899)))), null, List.of()))
            .path("query").path("bool").path("filter").get(0);

        assertThat(filter.path("range").path("date").path("gte").asString()).isEqualTo("1850-01-01T00:00:00Z");
        assertThat(filter.path("range").path("date").path("lt").asString()).isEqualTo("1900-01-01T00:00:00Z");
    }

    @Test
    void sortsBreakTiesByIdentifierAndTolerateMissingFields() {
        assertThat(ElasticsearchRequests.sort(null)).isEmpty();
        assertThat(ElasticsearchRequests.sort(" ")).isEmpty();
        assertThat(ElasticsearchRequests.sort("title_str asc")).containsExactly(
            Map.of("title_str", Map.of("order", "asc", "unmapped_type", "keyword")),
            Map.of("id", Map.of("order", "asc", "unmapped_type", "keyword")));
        assertThat(ElasticsearchRequests.sort("score desc,  lastModified DESC")).containsExactly(
            Map.of("_score", Map.of("order", "desc")),
            Map.of("lastModified", Map.of("order", "desc", "unmapped_type", "keyword")),
            Map.of("id", Map.of("order", "asc", "unmapped_type", "keyword")));
    }

    @Test
    void termFacetsListTheMostFrequentValuesOrValuesInOrder() {
        final JsonNode aggregations = body(query(null, List.of(), null, List.of(
            new IndexQuery.Terms("type", "type_str", 8, false), new IndexQuery.Terms("creator", "creator_str", 0, true))))
            .path("aggs");

        final JsonNode type = aggregations.path("type").path("aggs").path("values").path("terms");
        assertThat(type.path("field").asString()).isEqualTo("type_str");
        assertThat(type.path("size").asInt()).isEqualTo(8);
        assertThat(type.path("min_doc_count").asInt()).isEqualTo(1);
        assertThat(type.path("order").path("_count").asString()).isEqualTo("desc");
        final JsonNode creator = aggregations.path("creator").path("aggs").path("values").path("terms");
        assertThat(creator.path("order").path("_key").asString()).isEqualTo("asc");
        assertThat(creator.path("size").asInt()).as("Elasticsearch needs a positive size").isEqualTo(1);
        assertThat(aggregations.path("type").path("filter").has("match_all")).isTrue();
    }

    @Test
    void yearFacetsCountOneRangePerGapUntilTheirEnd() {
        final JsonNode ranges = body(query(null, List.of(), null, List.of(new IndexQuery.Years("year", "date", 1990, 2027, 10))))
            .path("aggs").path("year").path("aggs").path("values").path("date_range").path("ranges");

        assertThat(ranges.valueStream().map(range -> range.path("key").asString())).containsExactly(
            "1990-01-01T00:00:00Z", "2000-01-01T00:00:00Z", "2010-01-01T00:00:00Z", "2020-01-01T00:00:00Z");
        assertThat(ranges.get(0).path("from").asString()).isEqualTo("1990-01-01T00:00:00Z");
        assertThat(ranges.get(0).path("to").asString()).isEqualTo("2000-01-01T00:00:00Z");
        assertThat(ranges.get(3).path("to").asString()).as("the last range ends with the facet")
            .isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void yearFacetsWithoutRangesAreLeftOut() {
        final JsonNode body = body(query(null, List.of(), null, List.of(new IndexQuery.Years("year", "date", 1990, 2027, 0),
            new IndexQuery.Years("era", "date", 2027, 1990, 10))));

        assertThat(body.has("aggs")).isFalse();
    }

    @Test
    void escapesRegularExpressions() {
        assertThat(ElasticsearchRequests.regex("http://a-b.org/c d/é")).isEqualTo("http\\:\\/\\/a\\-b\\.org\\/c\\ d\\/é");
    }

}
