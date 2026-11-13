package com.articulorium.index.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import com.articulorium.index.backend.IndexStatus;
import com.articulorium.index.config.SolrProperties;
import com.articulorium.index.solr.SolrIndexClient;

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
        properties.setCollection("articulorium");
        client = new SolrIndexClient(builder, properties);
    }

    @Test
    void listsCollections() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[\"a\",\"articulorium\"]}", MediaType.APPLICATION_JSON));

        assertThat(client.listCollections()).containsExactly("a", "articulorium");
        server.verify();
    }

    @Test
    void createsMissingCollection() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[]}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET))
            .andExpect(queryParam("action", "CREATE"))
            .andExpect(queryParam("name", "articulorium"))
            .andExpect(queryParam("numShards", "1"))
            .andExpect(queryParam("collection.configName", "_default"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureCollection()).isTrue();
        server.verify();
    }

    @Test
    void keepsExistingCollection() {
        server.expect(requestTo(BASE + "/admin/collections?action=LIST&wt=json"))
            .andRespond(withSuccess("{\"collections\":[\"articulorium\"]}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureCollection()).isFalse();
        server.verify();
    }

    @Test
    void declaresMissingFieldsOnly() {
        server.expect(requestTo(BASE + "/articulorium/schema/fields?wt=json"))
            .andRespond(withSuccess("{\"fields\":[{\"name\":\"id\"},{\"name\":\"created\",\"type\":\"pdates\"}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/articulorium/schema?wt=json"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"add-field\":[{\"name\":\"lastModified\",\"type\":\"pdate\",\"stored\":true},"
                + "{\"name\":\"date\",\"type\":\"pdates\",\"stored\":true}]}", org.springframework.test.json.JsonCompareMode.STRICT))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        final Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("created", "pdate");
        fields.put("lastModified", "pdate");
        fields.put("date", "pdates");
        assertThat(client.ensureFields(fields)).containsExactly("lastModified", "date");
        server.verify();
    }

    @Test
    void declaresNothingWhenEveryFieldExists() {
        server.expect(requestTo(BASE + "/articulorium/schema/fields?wt=json"))
            .andRespond(withSuccess("{\"fields\":[{\"name\":\"date\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.ensureFields(Map.of("date", "pdates"))).isEmpty();
        assertThat(client.ensureFields(Map.of())).isEmpty();
        server.verify();
    }

    @Test
    void addsDocumentsAsJsonArrays() {
        server.expect(requestToUriTemplate(BASE + "/articulorium/update?commitWithin=1000&wt=json"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("[{\"id\":\"http://h/a\",\"title\":[\"A\"]}]"))
            .andRespond(withSuccess());

        client.add(Map.of("id", "http://h/a", "title", List.of("A")));
        server.verify();
    }

    @Test
    void deletesDocumentsById() {
        server.expect(requestToUriTemplate(BASE + "/articulorium/update?commitWithin=1000&wt=json"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"delete\":{\"id\":\"http://h/a\"}}"))
            .andRespond(withSuccess());

        client.deleteById("http://h/a");
        server.verify();
    }

    @Test
    void pingsTheCollection() {
        server.expect(requestTo(BASE + "/articulorium/admin/ping?wt=json"))
            .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));

        assertThat(client.ping()).isEqualTo("OK");
        server.verify();
    }

    @Test
    void countsDocuments() {
        server.expect(requestTo(BASE + "/articulorium/select?q=*:*&rows=0&wt=json"))
            .andRespond(withSuccess("{\"response\":{\"numFound\":12,\"docs\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.countDocuments()).isEqualTo(12L);
        server.verify();
    }

    @Test
    void countIsUnknownWithoutResponse() {
        server.expect(requestTo(BASE + "/articulorium/select?q=*:*&rows=0&wt=json"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.countDocuments()).isEqualTo(-1L);
    }

    @Test
    void reportsItsConditionFromThePingAndTheDocumentCount() {
        server.expect(requestTo(BASE + "/articulorium/admin/ping?wt=json"))
            .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/articulorium/select?q=*:*&rows=0&wt=json"))
            .andRespond(withSuccess("{\"response\":{\"numFound\":12,\"docs\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.status()).isEqualTo(new IndexStatus(true, "OK", 12));
        server.verify();
    }

    @Test
    void isUnavailableWhenThePingReportsAProblem() {
        server.expect(requestTo(BASE + "/articulorium/admin/ping?wt=json"))
            .andRespond(withSuccess("{\"status\":\"FAIL\"}", MediaType.APPLICATION_JSON));

        assertThat(client.status()).isEqualTo(new IndexStatus(false, "FAIL", -1));
        server.verify();
    }

    @Test
    void describesItselfAsTheSolrBackend() {
        assertThat(client.name()).isEqualTo("solr");
        assertThat(client.target()).isEqualTo("Solr collection articulorium");
        assertThat(client.location()).containsEntry("baseUrl", BASE).containsEntry("collection", "articulorium");
    }

}
