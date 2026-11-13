package com.articulorium.triplestore.blazegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link BlazegraphStoreClient} against a mocked Blazegraph server.
 */
@ExtendWith(OutputCaptureExtension.class)
class BlazegraphStoreClientTest {

    private static final String SERVICE = "http://blazegraph:8080/bigdata";

    private static final String SPARQL = SERVICE + "/namespace/library/sparql";

    private static final String GRAPH = "http://localhost:8000/platform/books?x=1";

    private MockRestServiceServer server;

    private TriplestoreProperties.Blazegraph properties;

    private BlazegraphStoreClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new TriplestoreProperties.Blazegraph();
        properties.setServiceUrl(SERVICE + "/");
        properties.setNamespace("library");
        client = new BlazegraphStoreClient(builder, properties);
    }

    @Test
    void describesItself() {
        assertThat(client.name()).isEqualTo("blazegraph");
        assertThat(client.target()).contains("Blazegraph namespace library");
        assertThat(client.location()).containsEntry("endpoint", SPARQL).containsEntry("namespace", "library");
    }

    @Test
    void replacesAGraphByDroppingItAndPostingItsContent() {
        server.expect(requestTo(SPARQL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith("application/sparql-update"))
            .andExpect(content().string("DROP SILENT GRAPH <" + GRAPH + ">"))
            .andRespond(withSuccess("<html>ok</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(SPARQL + "?context-uri=http%3A%2F%2Flocalhost%3A8000%2Fplatform%2Fbooks%3Fx%3D1"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith("text/turtle"))
            .andExpect(content().string("<a> <b> \"c\" .\n"))
            .andRespond(withSuccess("<data modified=\"1\"/>", MediaType.APPLICATION_XML));

        client.replaceGraph(GRAPH, "<a> <b> \"c\" .\n");
        server.verify();
    }

    @Test
    void dropsGraphsSilentlyAndEmptyGraphsAreOnlyDropped() {
        server.expect(requestTo(SPARQL)).andExpect(content().string("DROP SILENT GRAPH <" + GRAPH + ">"))
            .andRespond(withSuccess());
        server.expect(requestTo(SPARQL)).andExpect(content().string("DROP SILENT GRAPH <" + GRAPH + ">"))
            .andRespond(withSuccess());

        client.dropGraph(GRAPH);
        client.replaceGraph(GRAPH, " \n");
        server.verify();
    }

    @Test
    void graphNamesCannotBreakOutOfTheUpdate() {
        server.expect(requestTo(SPARQL))
            .andExpect(content().string("DROP SILENT GRAPH <http://x/%3E%20;%20CLEAR%20ALL%20;%20#%3C%5Cu003E>"))
            .andRespond(withSuccess());

        client.dropGraph("http://x/> ; CLEAR ALL ; #<\\u003E");
        server.verify();
    }

    @Test
    void theContentIsNotPostedWhenTheDropFails() {
        server.expect(requestTo(SPARQL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(never(), anything());

        assertThatThrownBy(() -> client.replaceGraph(GRAPH, "<a> <b> <c> .\n"))
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void aRefusedContentPropagates() {
        server.expect(requestTo(SPARQL)).andRespond(withSuccess());
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.replaceGraph(GRAPH, "<a> <b> <c> .\n"))
            .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void queriesThePingAndTheGraphCountOnTheNamespace() {
        server.expect(requestTo(SPARQL))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andRespond(withSuccess("{\"head\":{},\"boolean\":true}", SparqlStoreClient.SPARQL_RESULTS_JSON));
        server.expect(requestTo(SPARQL))
            .andRespond(withSuccess("{\"head\":{\"vars\":[\"graphs\"]},\"results\":{\"bindings\":"
                + "[{\"graphs\":{\"type\":\"literal\",\"value\":\"5\"}}]}}", SparqlStoreClient.SPARQL_RESULTS_JSON));

        assertThat(client.ping()).isTrue();
        assertThat(client.countGraphs()).isEqualTo(5);
        server.verify();
    }

    @Test
    void createsAMissingNamespaceInQuadsMode() {
        server.expect(requestTo(SERVICE + "/namespace"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("com.bigdata.rdf.sail.namespace=library\n"),
                org.hamcrest.Matchers.containsString("com.bigdata.rdf.store.AbstractTripleStore.quads=true\n"),
                org.hamcrest.Matchers.containsString("com.bigdata.rdf.sail.truthMaintenance=false\n"))))
            .andRespond(withStatus(HttpStatus.CREATED).body("CREATED: library"));

        assertThat(client.prepare()).isTrue();
        server.verify();
    }

    @Test
    void usesAnExistingQuadsNamespace(final CapturedOutput output) {
        server.expect(requestTo(SERVICE + "/namespace")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(SERVICE + "/namespace/library/properties"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("#comment\ncom.bigdata.rdf.store.AbstractTripleStore.quads = true\n",
                MediaType.TEXT_PLAIN));

        assertThat(client.prepare()).isFalse();
        assertThat(output).doesNotContain("quads mode");
        server.verify();
    }

    @Test
    void reportsAnExistingNamespaceWithoutNamedGraphs(final CapturedOutput output) {
        server.expect(requestTo(SERVICE + "/namespace")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(SERVICE + "/namespace/library/properties"))
            .andRespond(withSuccess("com.bigdata.rdf.store.AbstractTripleStore.quads=false\n", MediaType.TEXT_PLAIN));

        assertThat(client.prepare()).isFalse();
        assertThat(output).contains("namespace library is not in quads mode");
    }

    @Test
    void anUnreadableExistingNamespaceIsUsedAsItIs() {
        server.expect(requestTo(SERVICE + "/namespace")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(SERVICE + "/namespace/library/properties"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.prepare()).isFalse();
    }

    @Test
    void anUnreachableServerFailsThePreparation() {
        server.expect(requestTo(SERVICE + "/namespace")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(never(), anything());

        assertThatThrownBy(client::prepare).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void authenticatesWithBasicCredentialsWhenConfigured() {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer secured = MockRestServiceServer.bindTo(builder).build();
        properties.setUsername("admin");
        properties.setPassword("s3cret");
        secured.expect(requestTo(SPARQL)).andExpect(header("Authorization", "Basic YWRtaW46czNjcmV0"))
            .andRespond(withSuccess());

        new BlazegraphStoreClient(builder, properties).dropGraph(GRAPH);
        secured.verify();
    }

    @Test
    void theNamespaceIsRequired() {
        properties.setNamespace("");
        assertThatThrownBy(() -> new BlazegraphStoreClient(RestClient.builder(), properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("articulorium.triplestore.blazegraph.namespace");
    }

}
