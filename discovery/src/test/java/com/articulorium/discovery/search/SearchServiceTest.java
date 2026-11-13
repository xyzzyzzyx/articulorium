package com.articulorium.discovery.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
import com.articulorium.discovery.solr.SolrRequests;
import com.articulorium.discovery.solr.SolrSchema;
import com.articulorium.discovery.solr.SolrSearchClient;
import com.articulorium.discovery.solr.SolrSearchIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Tests how searches become Solr queries and how results are filtered, against a mocked Solr and
 * mocked access checks.
 */
class SearchServiceTest {

    private static final String P = "http://localhost:8000/platform/";

    private static final String SELECT = "http://solr.test/solr/articulorium/select";

    private static final String RESPONSE = """
        {"response": {"numFound": 42, "docs": [
          {"id": "%1$scommunities/c/moby", "type": ["http://pcdm.org/models#Object", "http://purl.org/ontology/bibo/Book"],
           "title": ["Moby Dick", "Moby Dick oder Der Wal"], "title_txt_de": ["Moby Dick oder Der Wal"],
           "description": ["A whale of a tale"], "date": ["1851-10-18T00:00:00Z"], "thumbnail": ["javascript:alert(1)"]},
          {"id": "%1$scommunities/c/secret", "type": ["http://pcdm.org/models#Object"], "title": ["Secret"]},
          {"id": "%1$scommunities/c", "type": ["https://articulorium.org/ns/repository#Collection"],
           "thumbnail": ["https://images.example.org/c.jpg"]},
          {"id": "http://elsewhere.example.org/x", "title": ["Not ours"]},
          {"id": "http://articulorium-platform/communities/c/atlas", "type": ["http://pcdm.org/models#Object"],
           "title": ["Atlas"]}
        ]},
         "facet_counts": {
           "facet_fields": {"type": ["http://pcdm.org/models#Object", 40, "https://articulorium.org/ns/repository#Collection", 2]},
           "facet_ranges": {"year": {"counts": ["1800-01-01T00:00:00Z", 0, "1850-01-01T00:00:00Z", 12, "2000-01-01T00:00:00Z", 3]}}
         }}
        """.formatted(P);

    private MockRestServiceServer solr;

    private DiscoveryProperties properties;

    private AccessVerifier access;

    private SolrSchema schema;

    private SearchService service;

    private final MultiValueMap<String, String> sent = new LinkedMultiValueMap<>();

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        solr = MockRestServiceServer.bindTo(builder).build();
        properties = settings();
        access = mock(AccessVerifier.class);
        when(access.readable(any(), any())).thenAnswer(invocation -> {
            final List<String> readable = new ArrayList<>(invocation.<Collection<String>>getArgument(0));
            readable.removeIf(path -> path.endsWith("secret"));
            return readable;
        });
        schema = mock(SolrSchema.class);
        when(schema.has(any())).thenReturn(true);
        service = new SearchService(new SolrSearchIndex(new SolrSearchClient(builder, properties), schema), access,
            new PublicUrls(properties), properties, Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC));
    }

    static DiscoveryProperties settings() {
        final DiscoveryProperties properties = new DiscoveryProperties();
        properties.getSolr().setBaseUrl("http://solr.test/solr");
        final DiscoveryProperties.Search search = properties.getSearch();
        search.getFields().add(field("title", 10, true));
        search.getFields().add(field("description", 2.5, false));
        search.getFields().add(field("_text_", 1, false));
        search.getPhraseFields().add(field("title", 20, false));
        search.getBoostQueries().add("type_str:\"https://articulorium.org/ns/repository#Collection\"^2");
        search.getBaseFilters().add("type_str:(\"http://pcdm.org/models#Object\")");
        search.getBaseFilters().add("-id:*\\/members\\/*");
        final DiscoveryProperties.Facet type = new DiscoveryProperties.Facet();
        type.setId("type");
        type.setField("type_str");
        type.setLimit(5);
        search.getFacets().add(type);
        final DiscoveryProperties.Facet year = new DiscoveryProperties.Facet();
        year.setId("year");
        year.setField("date");
        year.setType(DiscoveryProperties.FacetType.YEAR_RANGE);
        year.setStart(1800);
        year.setGap(50);
        search.getFacets().add(year);
        final DiscoveryProperties.Filter digitized = new DiscoveryProperties.Filter();
        digitized.setId("digitized");
        digitized.setQuery("hasFile_str:*");
        search.getFilters().add(digitized);
        search.getSorts().add(sort("relevance", "score desc"));
        search.getSorts().add(sort("title", "title_str asc"));
        return properties;
    }

    private static DiscoveryProperties.SearchField field(final String name, final double boost, final boolean localized) {
        final DiscoveryProperties.SearchField field = new DiscoveryProperties.SearchField();
        field.setField(name);
        field.setBoost(boost);
        field.setLocalized(localized);
        return field;
    }

    private static DiscoveryProperties.Sort sort(final String id, final String value) {
        final DiscoveryProperties.Sort sort = new DiscoveryProperties.Sort();
        sort.setId(id);
        sort.setValue(value);
        return sort;
    }

    private void expectSolr() {
        solr.expect(requestTo(SELECT)).andExpect(SolrRequests.capture(sent))
            .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
    }

    private static SearchRequest request(final String query, final Map<String, List<String>> facets,
            final Set<String> filters, final String sort) {
        return new SearchRequest(query, 2, 10, sort, facets, filters, null, null, "de-AT", null);
    }

    @Test
    void searchesConfiguredFieldsInTheirOrderOfPrecedence() {
        expectSolr();

        service.search(request("whale", Map.of(), Set.of(), null));

        assertThat(sent.get("defType")).containsExactly("edismax");
        assertThat(sent.get("q")).containsExactly("whale");
        assertThat(sent.get("qf")).containsExactly("title^10 title_txt_de^10 description^2.5 _text_");
        assertThat(sent.get("pf")).containsExactly("title^20");
        assertThat(sent.get("bq")).containsExactly("type_str:\"https://articulorium.org/ns/repository#Collection\"^2");
        assertThat(sent.get("uf")).containsExactly("-*");
        assertThat(sent.get("fq")).containsExactly("type_str:(\"http://pcdm.org/models#Object\")", "-id:*\\/members\\/*");
        assertThat(sent.get("start")).containsExactly("10");
        assertThat(sent.get("rows")).containsExactly("10");
        // the first sort order (relevance) needs no sort parameter
        assertThat(SolrRequests.values(sent, "sort")).isEmpty();
        assertThat(sent.get("fl").getFirst().split(",")).contains("id", "type", "title", "title_txt_de", "description",
            "description_txt_de", "date", "thumbnail");
    }

    @Test
    void searchesOnlyFieldsTheCollectionHas() {
        expectSolr();
        // the collection has no document with a description yet, nor German titles
        when(schema.has("description")).thenReturn(false);
        when(schema.has("title_txt_de")).thenReturn(false);

        service.search(request("whale", Map.of(), Set.of(), null));

        assertThat(sent.get("qf")).containsExactly("title^10 _text_");
        assertThat(sent.get("pf")).containsExactly("title^20");
    }

    @Test
    void searchesEverythingWhileNoConfiguredFieldExists() {
        expectSolr();
        when(schema.has(any())).thenReturn(false);

        service.search(request("whale", Map.of(), Set.of(), null));

        assertThat(sent.get("qf")).containsExactly("_text_");
        assertThat(SolrRequests.values(sent, "pf")).isEmpty();
    }

    @Test
    void asksForFacetsThatIgnoreTheirOwnSelection() {
        expectSolr();

        service.search(request("whale", Map.of("type", List.of("http://pcdm.org/models#Object", "say \"hi\"")),
            Set.of(), null));

        assertThat(sent.get("facet")).containsExactly("true");
        assertThat(sent.get("facet.field")).containsExactly("{!ex=type key=type}type_str");
        assertThat(sent.get("f.type_str.facet.limit")).containsExactly("5");
        assertThat(sent.get("facet.range")).containsExactly("{!ex=year key=year}date");
        assertThat(sent.get("f.date.facet.range.start")).containsExactly("1800-01-01T00:00:00Z");
        assertThat(sent.get("f.date.facet.range.end")).containsExactly("2027-01-01T00:00:00Z");
        assertThat(sent.get("f.date.facet.range.gap")).containsExactly("+50YEARS");
        assertThat(sent.get("fq")).contains(
            "{!tag=type}type_str:(\"http://pcdm.org/models#Object\" OR \"say \\\"hi\\\"\")");
    }

    @Test
    void appliesYearRangesFiltersSortsAndContainers() {
        expectSolr();

        service.search(new SearchRequest("", 1, 0, "title", Map.of("year", List.of("1850-1899")), Set.of("digitized", "unknown"),
            "/communities/c/", ResourceKind.COLLECTION, null, null));

        assertThat(sent.get("q")).containsExactly("*:*");
        assertThat(SolrRequests.values(sent, "defType")).isEmpty();
        assertThat(sent.get("fq")).contains(
            "{!tag=year}(date:[1850-01-01T00:00:00Z TO 1900-01-01T00:00:00Z})",
            "hasFile_str:*",
            "(id:/" + P.replace(":", "\\:").replace("/", "\\/").replace(".", "\\.")
                + "communities\\/c\\/[^\\/]+/ OR id:/http\\:\\/\\/articulorium\\-platform\\/communities\\/c\\/[^\\/]+/)",
            "type_str:(\"https://articulorium.org/ns/repository#Collection\" OR \"http://pcdm.org/models#Collection\")");
        assertThat(sent.get("sort")).containsExactly("title_str asc, id asc");
        assertThat(sent.get("rows")).containsExactly("20");
    }

    @Test
    void browsesMostRecentFirst() {
        expectSolr();

        final SearchResponse response = service.search(new SearchRequest(" ", 1, 5, null, Map.of(), Set.of(), null, null,
            null, null));

        assertThat(sent.get("sort")).containsExactly("lastModified desc, id asc");
        assertThat(response.sort()).isNull();
    }

    @Test
    void listsReadableResultsInTheVisitorsLanguage() {
        expectSolr();

        final SearchResponse response = service.search(request("whale", Map.of("year", List.of("1950-1999")),
            Set.of("digitized"), "title"));

        assertThat(response.total()).isEqualTo(42);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.sort()).isEqualTo("title");
        assertThat(response.filters()).containsExactly("digitized");
        assertThat(response.hidden()).isEqualTo(2);
        // resources written by services that reach the platform directly carry its internal address
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

    @Test
    void checksResultsWithTheVisitorsCredentials() {
        expectSolr();
        doReturn(List.of("communities/c/secret")).when(access).readable(any(), eq("Bearer alice"));

        final SearchResponse response = service.search(new SearchRequest("x", 1, 10, null, Map.of(), Set.of(), null,
            null, null, "Bearer alice"));

        assertThat(response.results()).extracting(SearchResponse.Result::title).containsExactly("Secret");
    }

    @Test
    void refusesRequestsOutOfBounds() {
        assertThatThrownBy(() -> service.search(request("x".repeat(501), Map.of(), Set.of(), null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new SearchRequest("x", 1001, 10, null, Map.of(), Set.of(), null, null,
            null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(request("x", Map.of(), Set.of(), "random")))
            .hasMessageContaining("Unknown sort order");
        assertThatThrownBy(() -> service.search(request("x", Map.of("year", List.of("1900 TO *")), Set.of(), null)))
            .hasMessageContaining("Not a year range");
        assertThatThrownBy(() -> service.search(new SearchRequest("", 1, 10, null, Map.of(), Set.of(), "a/../b", null,
            null, null))).isInstanceOf(IllegalArgumentException.class);
        solr.verify();
    }

}
