package com.articulorium.discovery.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.articulorium.discovery.config.DiscoveryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Tests the Solr client against a mocked Solr.
 */
class SolrSearchClientTest {

    private MockRestServiceServer solr;

    private SolrSearchClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        solr = MockRestServiceServer.bindTo(builder).build();
        final DiscoveryProperties properties = new DiscoveryProperties();
        properties.getSolr().setBaseUrl("http://solr.test/solr");
        client = new SolrSearchClient(builder, properties);
    }

    @Test
    void postsQueriesAsForms() {
        final MultiValueMap<String, String> sent = new LinkedMultiValueMap<>();
        solr.expect(requestTo("http://solr.test/solr/articulorium/select")).andExpect(method(HttpMethod.POST))
            .andExpect(SolrRequests.capture(sent))
            .andRespond(withSuccess("{\"response\": {\"numFound\": 0, \"docs\": []}}", MediaType.APPLICATION_JSON));
        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "whale & co");
        params.add("fq", "a");
        params.add("fq", "b");

        assertThat(client.select(params).path("response").path("numFound").asLong()).isZero();
        assertThat(sent.get("q")).containsExactly("whale & co");
        assertThat(sent.get("fq")).containsExactly("a", "b");
        assertThat(sent.get("wt")).containsExactly("json");
    }

    @Test
    void readsDocumentsByIdentifierWithTheTermParser() {
        final MultiValueMap<String, String> sent = new LinkedMultiValueMap<>();
        solr.expect(requestTo("http://solr.test/solr/articulorium/select")).andExpect(SolrRequests.capture(sent))
            .andRespond(withSuccess("{\"response\": {\"docs\": [{\"id\": \"http://x/a\"}]}}", MediaType.APPLICATION_JSON));

        assertThat(client.get("http://x/a\" OR *:*", "id")).hasValueSatisfying(doc ->
            assertThat(doc.path("id").asString()).isEqualTo("http://x/a"));
        assertThat(sent.get("q")).containsExactly("{!term f=id}http://x/a\" OR *:*");
    }

    @Test
    void hidesSolrsDiagnostics() {
        solr.expect(requestTo("http://solr.test/solr/articulorium/select")).andRespond(withServerError().body("secret trace"));

        assertThatThrownBy(() -> client.select(new LinkedMultiValueMap<>()))
            .isInstanceOf(SolrUnavailableException.class)
            .hasMessage("The search index is unavailable");
    }

}
