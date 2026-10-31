package com.articulorum.index.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import com.articulorum.index.config.SolrProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link SolrIndexClient} against a mocked Solr server.
 */
class SolrIndexClientTest {

    private static final String BASE = "http://solr:8983/solr";

    private MockRestServiceServer server;

    private SolrIndexClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final SolrProperties properties = new SolrProperties();
        properties.setBaseUrl(BASE);
        properties.setCollection("articulorum");
        client = new SolrIndexClient(builder, properties);
    }

    @Test
    void listsCollections() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[\"a\",\"articulorum\"]}", MediaType.APPLICATION_JSON));

        assertThat(client.listCollections()).containsExactly("a", "articulorum");
        server.verify();
    }

    @Test
    void createsMissingCollection() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[]}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET))
            .andExpect(queryParam("action", "CREATE"))
            .andExpect(queryParam("name", "articulorum"))
            .andExpect(queryParam("numShards", "1"))
            .andExpect(queryParam("collection.configName", "_default"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureCollection()).isTrue();
        server.verify();
    }

    @Test
    void keepsExistingCollection() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[\"articulorum\"]}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureCollection()).isFalse();
        server.verify();
    }

    @Test
    void addsDocumentsAsJsonArrays() {
        server.expect(requestToUriTemplate(BASE + "/articulorum/update?commitWithin=1000&wt=json"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("[{\"id\":\"http://h/a\",\"title\":[\"A\"]}]"))
            .andRespond(withSuccess());

        client.add(Map.of("id", "http://h/a", "title", List.of("A")));
        server.verify();
    }

    @Test
    void deletesDocumentsById() {
        server.expect(requestToUriTemplate(BASE + "/articulorum/update?commitWithin=1000&wt=json"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"delete\":{\"id\":\"http://h/a\"}}"))
            .andRespond(withSuccess());

        client.deleteById("http://h/a");
        server.verify();
    }

    @Test
    void pingsTheCollection() {
        server.expect(requestTo(BASE + "/articulorum/admin/ping?wt=json"))
            .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));

        assertThat(client.ping()).isEqualTo("OK");
        server.verify();
    }

    @Test
    void countsDocuments() {
        server.expect(requestTo(BASE + "/articulorum/select?q=*:*&rows=0&wt=json"))
            .andRespond(withSuccess("{\"response\":{\"numFound\":12,\"docs\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.countDocuments()).isEqualTo(12L);
        server.verify();
    }

    @Test
    void countIsUnknownWithoutResponse() {
        server.expect(requestTo(BASE + "/articulorum/select?q=*:*&rows=0&wt=json"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.countDocuments()).isEqualTo(-1L);
    }

}
