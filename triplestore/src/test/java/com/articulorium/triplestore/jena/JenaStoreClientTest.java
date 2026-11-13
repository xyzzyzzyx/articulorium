package com.articulorium.triplestore.jena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.articulorium.triplestore.config.TriplestoreProperties;
import com.articulorium.triplestore.sparql.SparqlStoreClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link JenaStoreClient} against a mocked Fuseki server.
 */
class JenaStoreClientTest {

    private static final String DATASET = "http://fuseki:3030/library";

    private static final String GRAPH = DATASET + "/data?graph=http%3A%2F%2Flocalhost%3A8000%2Fplatform%2Fbooks%3Fx%3D1";

    /** admin:s3cret */
    private static final String BASIC = "Basic YWRtaW46czNjcmV0";

    private MockRestServiceServer server;

    private TriplestoreProperties.Jena properties;

    private JenaStoreClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new TriplestoreProperties.Jena();
        properties.setServiceUrl("http://fuseki:3030/");
        properties.setDataset("/library");
        properties.setUsername("admin");
        properties.setPassword("s3cret");
        client = new JenaStoreClient(builder, properties);
    }

    @Test
    void describesItselfWithoutCredentials() {
        assertThat(client.name()).isEqualTo("jena");
        assertThat(client.target()).isEqualTo("Apache Jena Fuseki dataset " + DATASET);
        assertThat(client.location()).containsEntry("endpoint", DATASET + "/sparql").containsEntry("dataset", "/library")
            .doesNotContainValue("s3cret");
        assertThat(client.prepare()).isFalse();
        server.verify();
    }

    @Test
    void replacesGraphsWithTheGraphStoreProtocolOfTheDataService() {
        server.expect(requestTo(GRAPH))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", BASIC))
            .andExpect(content().contentTypeCompatibleWith("text/turtle"))
            .andExpect(content().string("<a> <b> <c> .\n"))
            .andRespond(withStatus(HttpStatus.CREATED));

        client.replaceGraph("http://localhost:8000/platform/books?x=1", "<a> <b> <c> .\n");
        server.verify();
    }

    @Test
    void dropsGraphsAndIgnoresMissingOnes() {
        server.expect(requestTo(GRAPH)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(GRAPH)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.dropGraph("http://localhost:8000/platform/books?x=1");
        client.replaceGraph("http://localhost:8000/platform/books?x=1", "");
        server.verify();
    }

    @Test
    void refusedChangesPropagate() {
        server.expect(requestTo(GRAPH)).andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.replaceGraph("http://localhost:8000/platform/books?x=1", "<a> <b> <c> .\n"))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void updatesGoToTheUpdateService() {
        server.expect(requestTo(DATASET + "/update"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BASIC))
            .andExpect(content().contentTypeCompatibleWith("application/sparql-update"))
            .andExpect(content().string("CLEAR ALL"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.update("CLEAR ALL");
        server.verify();
    }

    @Test
    void queriesGoToTheQueryService() {
        server.expect(requestTo(DATASET + "/sparql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andRespond(withSuccess("{\"head\":{},\"boolean\":true}", SparqlStoreClient.SPARQL_RESULTS_JSON));
        server.expect(requestTo(DATASET + "/sparql"))
            .andRespond(withSuccess("{\"head\":{\"vars\":[\"graphs\"]},\"results\":{\"bindings\":"
                + "[{\"graphs\":{\"type\":\"literal\",\"value\":\"3\"}}]}}", SparqlStoreClient.SPARQL_RESULTS_JSON));

        assertThat(client.ask("ASK {}")).isTrue();
        assertThat(client.countGraphs()).isEqualTo(3);
        server.verify();
    }

    @Test
    void pingsWithAQuery() {
        server.expect(requestTo(DATASET + "/sparql"))
            .andExpect(content().formData(form("query", "ASK { ?s ?p ?o }")))
            .andRespond(withSuccess("{\"head\":{},\"boolean\":false}", SparqlStoreClient.SPARQL_RESULTS_JSON));

        assertThat(client.ping()).isTrue();
        server.verify();
    }

    @Test
    void sendsNoCredentialsWithoutAUserName() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer open = MockRestServiceServer.bindTo(builder).build();
        open.expect(requestTo("http://fuseki:3030/articulorium/update"))
            .andExpect(request -> assertThat(request.getHeaders().containsHeader("Authorization")).isFalse())
            .andRespond(withSuccess());

        new JenaStoreClient(builder, new TriplestoreProperties.Jena()).update("CLEAR ALL");
        open.verify();
    }

    @Test
    void theDatasetIsRequired() {
        properties.setDataset(" ");
        assertThatThrownBy(() -> new JenaStoreClient(RestClient.builder(), properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("articulorium.triplestore.jena.dataset");
    }

    private static org.springframework.util.MultiValueMap<String, String> form(final String name, final String value) {
        final org.springframework.util.MultiValueMap<String, String> form =
            new org.springframework.util.LinkedMultiValueMap<>();
        form.add(name, value);
        return form;
    }

}
