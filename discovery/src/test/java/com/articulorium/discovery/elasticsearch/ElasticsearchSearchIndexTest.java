package com.articulorium.discovery.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.index.IndexQuery;
import com.articulorium.discovery.index.IndexResult;
import com.articulorium.discovery.index.IndexUnavailableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tests the Elasticsearch search index against a mocked Elasticsearch.
 */
class ElasticsearchSearchIndexTest {

    private static final String SEARCH = "http://es.test:9200/articulorium/_search";

    private static final String EMPTY = "{\"hits\": {\"total\": {\"value\": 0, \"relation\": \"eq\"}, \"hits\": []}}";

    private MockRestServiceServer elasticsearch;

    private DiscoveryProperties properties;

    private ElasticsearchSearchIndex index;

    private final List<JsonNode> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        properties.getElasticsearch().setBaseUrl("http://es.test:9200");
        index = index(properties);
    }

    private ElasticsearchSearchIndex index(final DiscoveryProperties settings) {
        final RestClient.Builder builder = RestClient.builder();
        elasticsearch = MockRestServiceServer.bindTo(builder).build();
        return new ElasticsearchSearchIndex(builder, settings);
    }

    private static IndexQuery query(final List<String> fields, final int start, final int rows,
            final List<IndexQuery.Facet> facets) {
        return new IndexQuery(null, null, List.of(), fields, start, rows, null, facets);
    }

    @Test
    void postsSearchesAsJsonAndReadsTheDocuments() {
        elasticsearch.expect(requestTo(SEARCH)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
            .andExpect(ElasticsearchRequestBodies.capture(sent))
            .andRespond(withSuccess("""
                {"took": 3, "hits": {"total": {"value": 2, "relation": "eq"}, "hits": [
                  {"_index": "articulorium", "_id": "http://x/a", "_score": 1.0,
                   "_source": {"id": "http://x/a", "title": ["A"]}},
                  {"_index": "articulorium", "_id": "http://x/b", "_score": 0.5, "_source": {"id": "http://x/b"}},
                  {"_index": "articulorium", "_id": "http://x/c", "_score": 0.1}]}}
                """, MediaType.APPLICATION_JSON));

        final IndexResult result = index.search(query(List.of("id", "title"), 20, 10, List.of()));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.documents()).extracting(doc -> doc.path("id").asString())
            .containsExactly("http://x/a", "http://x/b");
        assertThat(result.documents().getFirst().path("title").get(0).asString()).isEqualTo("A");
        final JsonNode body = sent.getFirst();
        assertThat(body.path("from").asInt()).isEqualTo(20);
        assertThat(body.path("size").asInt()).isEqualTo(10);
        assertThat(ElasticsearchRequestBodies.strings(body.path("_source"))).containsExactly("id", "title");
    }

    @Test
    void asksForWholeDocumentsWhenEveryFieldIsWanted() {
        elasticsearch.expect(requestTo(SEARCH)).andExpect(ElasticsearchRequestBodies.capture(sent))
            .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        assertThat(index.search(query(List.of("*"), 0, 1, List.of())).documents()).isEmpty();
        assertThat(sent.getFirst().has("_source")).isFalse();
    }

    @Test
    void readsFacetCountsInElasticsearchsOrder() {
        elasticsearch.expect(requestTo(SEARCH)).andRespond(withSuccess("""
            {"hits": {"total": {"value": 5, "relation": "eq"}, "hits": []},
             "aggregations": {
               "type": {"doc_count": 5, "values": {"doc_count_error_upper_bound": 0, "sum_other_doc_count": 0,
                 "buckets": [{"key": "b", "doc_count": 3}, {"key": "a", "doc_count": 2}]}},
               "digitized": {"doc_count": 5, "values": {"buckets": [{"key": 1, "key_as_string": "true", "doc_count": 4}]}},
               "year": {"doc_count": 5, "values": {"buckets": [
                 {"key": "1850-01-01T00:00:00Z", "from": -3786825600000.0, "from_as_string": "1850-01-01T00:00:00.000Z",
                  "to": -3471292800000.0, "to_as_string": "1860-01-01T00:00:00.000Z", "doc_count": 5}]}}}}
            """, MediaType.APPLICATION_JSON));

        final IndexResult result = index.search(query(List.of("id"), 0, 10, List.of(
            new IndexQuery.Terms("type", "type_str", 8, false), new IndexQuery.Terms("digitized", "digitized", 2, false),
            new IndexQuery.Years("year", "date", 1850, 1860, 10), new IndexQuery.Terms("missing", "x_str", 5, false))));

        assertThat(result.facet("type")).containsExactly(new IndexResult.Count("b", 3), new IndexResult.Count("a", 2));
        assertThat(result.facet("digitized")).containsExactly(new IndexResult.Count("true", 4));
        assertThat(result.facet("year")).containsExactly(new IndexResult.Count("1850-01-01T00:00:00Z", 5));
        assertThat(result.facet("missing")).isEmpty();
    }

    @Test
    void readsNothingBeyondElasticsearchsResultWindow() {
        assertThat(index.search(query(List.of("id"), 10_000, 500, List.of()))).isEqualTo(IndexResult.empty());

        elasticsearch.expect(requestTo(SEARCH)).andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        index.search(query(List.of("id"), 9_500, 500, List.of()));
        elasticsearch.verify();
    }

    @Test
    void hidesElasticsearchsDiagnostics() {
        elasticsearch.expect(requestTo(SEARCH)).andRespond(withBadRequest().body("{\"error\": \"secret trace\"}"));

        assertThatThrownBy(() -> index.search(query(List.of("id"), 0, 10, List.of())))
            .isInstanceOf(IndexUnavailableException.class)
            .hasMessage("The search index is unavailable");
    }

    @Test
    void everyFieldCanBeNamedInASearch() {
        assertThat(index.hasField("no_such_field")).isTrue();
    }

    @Test
    void sendsNoCredentialsUnlessConfigured() {
        elasticsearch.expect(requestTo(SEARCH)).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        index.search(query(List.of("id"), 0, 10, List.of()));
        elasticsearch.verify();
    }

    @Test
    void authenticatesWithAnApiKey() {
        properties.getElasticsearch().setApiKey("a2V5OnNlY3JldA==");
        properties.getElasticsearch().setUsername("ignored");
        index = index(properties);
        elasticsearch.expect(requestTo(SEARCH)).andExpect(header("Authorization", "ApiKey a2V5OnNlY3JldA=="))
            .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        index.search(query(List.of("id"), 0, 10, List.of()));
        elasticsearch.verify();
    }

    @Test
    void authenticatesWithAUserAndPassword() {
        properties.getElasticsearch().setBaseUrl("http://es.test:9200/");
        properties.getElasticsearch().setUsername("elastic");
        properties.getElasticsearch().setPassword("changeme");
        index = index(properties);
        // elastic:changeme
        elasticsearch.expect(requestTo(SEARCH)).andExpect(header("Authorization", "Basic ZWxhc3RpYzpjaGFuZ2VtZQ=="))
            .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        index.search(query(List.of("id"), 0, 10, List.of()));
        elasticsearch.verify();
    }

}
