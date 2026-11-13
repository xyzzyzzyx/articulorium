package com.articulorium.discovery.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.articulorium.discovery.access.AccessVerifier;
import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;
import com.articulorium.discovery.elasticsearch.ElasticsearchRequestBodies;
import com.articulorium.discovery.elasticsearch.ElasticsearchSearchIndex;
import com.articulorium.discovery.index.SearchIndex;
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
import tools.jackson.databind.JsonNode;

/**
 * Tests the sitemap entries against a mocked Solr and a mocked Elasticsearch.
 */
class SitemapServiceTest {

    private static final String P = "http://localhost:8000/platform/";

    private DiscoveryProperties properties;

    private AccessVerifier access;

    private RestClient.Builder builder;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = SearchServiceTest.settings();
        properties.getElasticsearch().setBaseUrl("http://es.test:9200");
        access = mock(AccessVerifier.class);
        when(access.readable(anyCollection(), any())).thenAnswer(invocation -> {
            final List<String> readable = new ArrayList<>(invocation.<Collection<String>>getArgument(0));
            readable.removeIf(path -> path.endsWith("secret"));
            return readable;
        });
    }

    private SitemapService service(final SearchIndex index) {
        final PublicUrls urls = new PublicUrls(properties);
        return new SitemapService(index, access, urls, properties, new SearchService(index, access, urls, properties));
    }

    @Test
    void listsWhatAnonymousVisitorsMayReadMostRecentFirstFromSolr() {
        final MultiValueMap<String, String> sent = new LinkedMultiValueMap<>();
        server.expect(requestTo("http://solr.test/solr/articulorium/select")).andExpect(SolrRequests.capture(sent))
            .andRespond(withSuccess("""
                {"response": {"numFound": 3, "docs": [
                  {"id": "%1$scommunities/c/moby", "lastModified": "2026-11-01T10:00:00Z"},
                  {"id": "%1$scommunities/c/secret", "lastModified": "2026-10-01T10:00:00Z"},
                  {"id": "%1$scommunities/c"}]}}
                """.formatted(P), MediaType.APPLICATION_JSON));

        final List<SitemapService.Entry> entries = service(new SolrSearchIndex(new SolrSearchClient(builder, properties),
            mock(SolrSchema.class))).entries();

        assertThat(entries).containsExactly(new SitemapService.Entry("communities/c/moby", "2026-11-01T10:00:00Z"),
            new SitemapService.Entry("communities/c", null));
        assertThat(sent.get("q")).containsExactly("*:*");
        assertThat(sent.get("fq")).containsExactly("type_str:(\"http://pcdm.org/models#Object\")", "-id:*\\/members\\/*");
        assertThat(sent.get("fl")).containsExactly("id,lastModified");
        assertThat(sent.get("sort")).containsExactly("lastModified desc, id asc");
        assertThat(sent.get("start")).containsExactly("0");
        assertThat(sent.get("rows")).containsExactly("500");
        assertThat(SolrRequests.values(sent, "facet")).isEmpty();
    }

    @Test
    void listsWhatAnonymousVisitorsMayReadMostRecentFirstFromElasticsearch() {
        final List<JsonNode> sent = new ArrayList<>();
        server.expect(requestTo("http://es.test:9200/articulorium/_search"))
            .andExpect(ElasticsearchRequestBodies.capture(sent))
            .andRespond(withSuccess("""
                {"hits": {"total": {"value": 3, "relation": "eq"}, "hits": [
                  {"_source": {"id": "%1$scommunities/c/moby", "lastModified": ["2026-11-01T10:00:00Z"]}},
                  {"_source": {"id": "%1$scommunities/c/secret", "lastModified": ["2026-10-01T10:00:00Z"]}},
                  {"_source": {"id": "%1$scommunities/c"}}]}}
                """.formatted(P), MediaType.APPLICATION_JSON));

        final List<SitemapService.Entry> entries = service(new ElasticsearchSearchIndex(builder, properties)).entries();

        assertThat(entries).containsExactly(new SitemapService.Entry("communities/c/moby", "2026-11-01T10:00:00Z"),
            new SitemapService.Entry("communities/c", null));
        final JsonNode body = sent.getFirst();
        assertThat(body.path("query").path("bool").path("filter")).hasSize(2);
        assertThat(ElasticsearchRequestBodies.strings(body.path("_source"))).containsExactly("id", "lastModified");
        assertThat(body.path("sort").get(0).path("lastModified").path("order").asString()).isEqualTo("desc");
        assertThat(body.path("sort").get(1).path("id").path("order").asString()).isEqualTo("asc");
        assertThat(body.path("size").asInt()).isEqualTo(500);
        assertThat(body.has("aggs")).isFalse();
    }

    @Test
    void listsNothingWhenSitemapsAreTurnedOff() {
        properties.getSeo().setSitemap(false);

        assertThat(service(mock(SearchIndex.class)).entries()).isEmpty();
    }

}
