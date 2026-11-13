package com.articulorium.triplestore.qlever;

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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link QleverStoreClient} against a mocked QLever server.
 */
class QleverStoreClientTest {

    private static final String SERVICE = "http://qlever:7001";

    private static final String GRAPH = SERVICE + "/?graph=http%3A%2F%2Flocalhost%3A8000%2Fplatform%2Fbooks%3Fx%3D1";

    private MockRestServiceServer server;

    private QleverStoreClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final TriplestoreProperties properties = new TriplestoreProperties();
        properties.setServiceUrl(SERVICE + "/");
        properties.setAccessToken("t0ken");
        client = new QleverStoreClient(builder, properties);
    }

    @Test
    void describesItself() {
        assertThat(client.name()).isEqualTo("qlever");
        assertThat(client.target()).isEqualTo("QLever at http://qlever:7001/");
        assertThat(client.location()).containsOnly(org.assertj.core.api.Assertions.entry("endpoint", SERVICE + "/"));
        assertThat(client.prepare()).isFalse();
        server.verify();
    }

    @Test
    void replacesGraphsWithTheGraphStoreProtocol() {
        server.expect(requestTo(GRAPH))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer t0ken"))
            .andExpect(content().contentTypeCompatibleWith("text/turtle"))
            .andExpect(content().string("<a> <b> <c> .\n"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.replaceGraph("http://localhost:8000/platform/books?x=1", "<a> <b> <c> .\n");
        server.verify();
    }

    @Test
    void emptyGraphsAreDroppedAndMissingGraphsIgnored() {
        server.expect(requestTo(GRAPH)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.replaceGraph("http://localhost:8000/platform/books?x=1", "  ");
        server.verify();
    }

    @Test
    void dropFailuresPropagate() {
        server.expect(requestTo(GRAPH)).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.dropGraph("http://localhost:8000/platform/books?x=1"))
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void updatesUseTheSparqlUpdateMediaType() {
        server.expect(requestTo(SERVICE + "/"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith("application/sparql-update"))
            .andExpect(content().string("CLEAR ALL"))
            .andRespond(withStatus(HttpStatus.OK));

        client.update("CLEAR ALL");
        server.verify();
    }

    @Test
    void asksAndCountsGraphs() {
        server.expect(requestTo(SERVICE + "/"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andRespond(withSuccess("{\"head\":{},\"boolean\":true}", SparqlStoreClient.SPARQL_RESULTS_JSON));
        server.expect(requestTo(SERVICE + "/"))
            .andRespond(withSuccess("{\"head\":{\"vars\":[\"graphs\"]},\"results\":{\"bindings\":"
                + "[{\"graphs\":{\"type\":\"literal\",\"value\":\"42\"}}]}}", SparqlStoreClient.SPARQL_RESULTS_JSON));
        server.expect(requestTo(SERVICE + "/"))
            .andRespond(withSuccess("{}", SparqlStoreClient.SPARQL_RESULTS_JSON));

        assertThat(client.ask("ASK {}")).isTrue();
        assertThat(client.countGraphs()).isEqualTo(42);
        assertThat(client.countGraphs()).isEqualTo(-1);
    }

    @Test
    void pingsTheLivenessProbe() {
        server.expect(requestTo(SERVICE + "/ping")).andRespond(withSuccess());
        assertThat(client.ping()).isTrue();
    }

    @Test
    void sendsNoCredentialsWithoutAnAccessToken() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer open = MockRestServiceServer.bindTo(builder).build();
        open.expect(requestTo(SERVICE + "/ping"))
            .andExpect(request -> assertThat(request.getHeaders().containsHeader("Authorization")).isFalse())
            .andRespond(withSuccess());

        new QleverStoreClient(builder, new TriplestoreProperties()).ping();
        open.verify();
    }

}
