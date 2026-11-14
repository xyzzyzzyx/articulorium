package com.articulorium.discovery.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.elasticsearch.ElasticsearchRequestBodies;
import com.articulorium.discovery.elasticsearch.ElasticsearchSearchIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tests how searches become Elasticsearch queries, with the settings and the expectations of
 * {@link SearchServiceTest}: the same searches give the same results from either backend.
 */
class SearchServiceElasticsearchTest {

    private static final String P = "http://localhost:8000/platform/";

    private static final String SEARCH = "http://es.test:9200/articulorium/_search";

    private static final String RESPONSE = """
        {"took": 5, "hits": {"total": {"value": 42, "relation": "eq"}, "hits": [
          {"_id": "1", "_source": {"id": "%1$scommunities/c/moby",
           "type": ["http://pcdm.org/models#Object", "http://purl.org/ontology/bibo/Book"],
           "title": ["Moby Dick", "Moby Dick oder Der Wal"], "title_txt_de": ["Moby Dick oder Der Wal"],
           "description": ["A whale of a tale"], "date": ["1851-10-18T00:00:00Z"], "thumbnail": ["javascript:alert(1)"]}},
          {"_id": "2", "_source": {"id": "%1$scommunities/c/secret", "type": ["http://pcdm.org/models#Object"],
           "title": ["Secret"]}},
          {"_id": "3", "_source": {"id": "%1$scommunities/c", "type": ["https://articulorium.org/ns/repository#Collection"],
           "thumbnail": ["https://images.example.org/c.jpg"]}},
          {"_id": "4", "_source": {"id": "http://elsewhere.example.org/x", "title": ["Not ours"]}},
          {"_id": "5", "_source": {"id": "http://articulorium-platform/communities/c/atlas",
           "type": ["http://pcdm.org/models#Object"], "title": ["Atlas"]}}
        ]},
         "aggregations": {
           "type": {"doc_count": 42, "values": {"buckets": [
             {"key": "http://pcdm.org/models#Object", "doc_count": 40},
             {"key": "https://articulorium.org/ns/repository#Collection", "doc_count": 2}]}},
           "year": {"doc_count": 42, "values": {"buckets": [
             {"key": "1800-01-01T00:00:00Z", "doc_count": 0},
             {"key": "1850-01-01T00:00:00Z", "doc_count": 12},
             {"key": "1900-01-01T00:00:00Z", "doc_count": 0},
             {"key": "1950-01-01T00:00:00Z", "doc_count": 0},
             {"key": "2000-01-01T00:00:00Z", "doc_count": 3}]}}
         }}
        """.formatted(P);

    private MockRestServiceServer elasticsearch;

    private SearchService service;

    private final List<JsonNode> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        elasticsearch = MockRestServiceServer.bindTo(builder).build();
        final DiscoveryProperties properties = SearchServiceTest.settings();
        properties.getIndex().setBackend(DiscoveryProperties.Backend.ELASTICSEARCH);
        properties.getElasticsearch().setBaseUrl("http://es.test:9200");
        final AccessVerifier access = mock(AccessVerifier.class);
        when(access.readable(any(), any())).thenAnswer(invocation -> {
            final List<String> readable = new ArrayList<>(invocation.<Collection<String>>getArgument(0));
            readable.removeIf(path -> path.endsWith("secret"));
            return readable;
        });
        service = new SearchService(new ElasticsearchSearchIndex(builder, properties), access, new PublicUrls(properties),
            properties, Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC));
        elasticsearch.expect(requestTo(SEARCH)).andExpect(ElasticsearchRequestBodies.capture(sent))
            .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
    }

    private static SearchRequest request(final String query, final Map<String, List<String>> facets,
            final Set<String> filters, final String sort) {
        return new SearchRequest(query, 2, 10, sort, facets, filters, null, Set.of(), "de-AT", null);
    }

    private JsonNode body() {
        return sent.getFirst();
    }

    @Test
    void searchesConfiguredFieldsInTheirOrderOfPrecedence() {
        service.search(request("whale +\"moby dick\" title:x", Map.of(), Set.of(), null));

        final JsonNode bool = body().path("query").path("bool");
        final JsonNode terms = bool.path("must").get(0).path("simple_query_string");
        // the terms cannot address fields of their own: simple_query_string has no field syntax
        assertThat(terms.path("query").asString()).isEqualTo("whale +\"moby dick\" title:x");
        assertThat(ElasticsearchRequestBodies.strings(terms.path("fields")))
            .containsExactly("title^10", "title_txt_de^10", "description^2.5", "_text_");
        assertThat(terms.path("minimum_should_match").asString()).isEqualTo("2<-1 5<80%");
        assertThat(terms.path("default_operator").asString()).isEqualTo("or");
        final JsonNode phrase = bool.path("should").get(0).path("multi_match");
        assertThat(phrase.path("type").asString()).isEqualTo("phrase");
        assertThat(ElasticsearchRequestBodies.strings(phrase.path("fields"))).containsExactly("title^20");
        assertThat(bool.path("should").get(1).path("query_string").path("query").asString())
            .isEqualTo("type_str:\"https://articulorium.org/ns/repository#Collection\"^2");
        assertThat(bool.path("filter").valueStream().map(filter -> filter.path("query_string").path("query").asString()))
            .containsExactly("type_str:(\"http://pcdm.org/models#Object\")", "-id:*\\/members\\/*");
        assertThat(body().path("from").asInt()).isEqualTo(10);
        assertThat(body().path("size").asInt()).isEqualTo(10);
        // the first sort order (relevance) needs no sort
        assertThat(body().has("sort")).isFalse();
        assertThat(ElasticsearchRequestBodies.strings(body().path("_source"))).contains("id", "type", "title",
            "title_txt_de", "description", "description_txt_de", "date", "thumbnail");
    }

    @Test
    void asksForFacetsThatIgnoreTheirOwnSelection() {
        service.search(request("whale", Map.of("type", List.of("http://pcdm.org/models#Object", "say \"hi\""),
            "year", List.of("1850-1899")), Set.of(), null));

        // facet selections filter the results after the facets are counted
        final JsonNode selections = body().path("post_filter").path("bool").path("filter");
        assertThat(ElasticsearchRequestBodies.strings(selections.get(0).path("terms").path("type_str")))
            .containsExactly("http://pcdm.org/models#Object", "say \"hi\"");
        assertThat(selections.get(1).path("range").path("date").path("gte").asString()).isEqualTo("1850-01-01T00:00:00Z");
        assertThat(body().path("query").path("bool").path("filter")).hasSize(2);

        // every facet is counted with the other facets' selections, not its own
        final JsonNode type = body().path("aggs").path("type");
        assertThat(type.path("filter").path("bool").path("filter")).hasSize(1);
        assertThat(type.path("filter").path("bool").path("filter").get(0).has("range")).isTrue();
        assertThat(type.path("aggs").path("values").path("terms").path("field").asString()).isEqualTo("type_str");
        assertThat(type.path("aggs").path("values").path("terms").path("size").asInt()).isEqualTo(5);
        final JsonNode year = body().path("aggs").path("year");
        assertThat(year.path("filter").path("bool").path("filter")).hasSize(1);
        assertThat(year.path("filter").path("bool").path("filter").get(0).has("terms")).isTrue();
        final JsonNode ranges = year.path("aggs").path("values").path("date_range").path("ranges");
        assertThat(year.path("aggs").path("values").path("date_range").path("field").asString()).isEqualTo("date");
        assertThat(ranges).hasSize(5);
        assertThat(ranges.get(0).path("from").asString()).isEqualTo("1800-01-01T00:00:00Z");
        assertThat(ranges.get(0).path("to").asString()).isEqualTo("1850-01-01T00:00:00Z");
        assertThat(ranges.get(4).path("to").asString()).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void appliesYearRangesFiltersSortsAndContainers() {
        service.search(new SearchRequest("", 1, 0, "title", Map.of("year", List.of("1850-1899")), Set.of("digitized", "unknown"),
            "/communities/c/", Set.of(ResourceKind.COLLECTION), null, null));

        final JsonNode bool = body().path("query").path("bool");
        assertThat(bool.has("must")).isFalse();
        assertThat(bool.has("should")).isFalse();
        final JsonNode filters = bool.path("filter");
        assertThat(filters).hasSize(5);
        assertThat(filters.get(2).path("query_string").path("query").asString()).isEqualTo("hasFile_str:*");
        final JsonNode parents = filters.get(3).path("bool").path("should");
        assertThat(parents.valueStream().map(parent -> parent.path("regexp").path("id").path("value").asString()))
            .containsExactly("http\\:\\/\\/localhost\\:8000\\/platform\\/communities\\/c\\/[^/]+",
                "http\\:\\/\\/articulorium\\-platform\\/communities\\/c\\/[^/]+");
        assertThat(ElasticsearchRequestBodies.strings(filters.get(4).path("terms").path("type_str"))).containsExactly(
            "https://articulorium.org/ns/repository#Collection", "http://pcdm.org/models#Collection");
        final JsonNode range = body().path("post_filter").path("bool").path("filter").get(0).path("range").path("date");
        assertThat(range.path("gte").asString()).isEqualTo("1850-01-01T00:00:00Z");
        assertThat(range.path("lt").asString()).isEqualTo("1900-01-01T00:00:00Z");
        assertThat(body().path("sort").get(0).path("title_str").path("order").asString()).isEqualTo("asc");
        assertThat(body().path("sort").get(1).path("id").path("order").asString()).isEqualTo("asc");
        assertThat(body().path("size").asInt()).isEqualTo(20);
    }

    @Test
    void browsesMostRecentFirst() {
        final SearchResponse response = service.search(new SearchRequest(" ", 1, 5, null, Map.of(), Set.of(), null, Set.of(),
            null, null));

        assertThat(body().path("sort").get(0).path("lastModified").path("order").asString()).isEqualTo("desc");
        assertThat(response.sort()).isNull();
    }

    @Test
    void listsReadableResultsInTheVisitorsLanguage() {
        final SearchResponse response = service.search(request("whale", Map.of("year", List.of("1950-1999")),
            Set.of("digitized"), "title"));

        assertThat(response.total()).isEqualTo(42);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.sort()).isEqualTo("title");
        assertThat(response.filters()).containsExactly("digitized");
        assertThat(response.hidden()).isEqualTo(2);
        assertThat(response.results()).extracting(SearchResponse.Result::path)
            .containsExactly("communities/c/moby", "communities/c", "communities/c/atlas");
        final SearchResponse.Result moby = response.results().getFirst();
        assertThat(moby.title()).isEqualTo("Moby Dick oder Der Wal");
        assertThat(moby.description()).isEqualTo("A whale of a tale");
        assertThat(moby.kind()).isEqualTo(ResourceKind.OBJECT);
        assertThat(moby.date()).isEqualTo("1851-10-18T00:00:00Z");
        assertThat(moby.thumbnail()).as("only http(s) links").isNull();
        final SearchResponse.Result collection = response.results().get(1);
        assertThat(collection.title()).as("the path's last segment without a title").isEqualTo("c");
        assertThat(collection.kind()).isEqualTo(ResourceKind.COLLECTION);
        assertThat(collection.thumbnail()).isEqualTo("https://images.example.org/c.jpg");

        assertThat(response.facets()).hasSize(2);
        assertThat(response.facets().getFirst().values()).extracting(SearchResponse.FacetValue::value)
            .containsExactly("http://pcdm.org/models#Object", "https://articulorium.org/ns/repository#Collection");
        assertThat(response.facets().get(1).values()).containsExactly(
            new SearchResponse.FacetValue("1850-1899", 12, false),
            new SearchResponse.FacetValue("2000-2026", 3, false),
            new SearchResponse.FacetValue("1950-1999", 0, true));
    }

}
