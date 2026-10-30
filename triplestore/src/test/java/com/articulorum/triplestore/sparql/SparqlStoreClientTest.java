package com.articulorum.triplestore.sparql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.articulorum.triplestore.config.TriplestoreProperties;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link SparqlStoreClient} against a mocked Blazegraph server.
 */
class SparqlStoreClientTest {

    private static final String SERVICE = "http://blazegraph:8080/bigdata";

    private MockRestServiceServer server;

    private SparqlStoreClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final TriplestoreProperties properties = new TriplestoreProperties();
        properties.setServiceUrl(SERVICE + "/");
        properties.setNamespace("articulorum");
        client = new SparqlStoreClient(builder, properties);
    }

    @Test
    void createsMissingNamespaceInQuadsMode() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/properties"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(SERVICE + "/namespace"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(Matchers.containsString("AbstractTripleStore.quads\">true")))
            .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(client.ensureNamespace()).isTrue();
        server.verify();
    }

    @Test
    void keepsExistingNamespace() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/properties"))
            .andRespond(withSuccess("<properties/>", MediaType.APPLICATION_XML));

        assertThat(client.ensureNamespace()).isFalse();
        server.verify();
    }

    @Test
    void updatesAreSentAsFormEncodedSparql() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/sparql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(java.util.Map.of("update", "DROP SILENT GRAPH <http://h/a>")))
            .andRespond(withSuccess());

        client.dropGraph("http://h/a");
        server.verify();
    }

    @Test
    void replaceGraphDropsThenInserts() {
        final String update = SparqlStoreClient.replaceGraphUpdate("http://h/a", "<http://h/a> <http://p> \"o\" .\n");
        assertThat(update).startsWith("DROP SILENT GRAPH <http://h/a> ;")
            .contains("INSERT DATA { GRAPH <http://h/a> {\n<http://h/a> <http://p> \"o\" .\n} }");
    }

    @Test
    void replacingWithNothingOnlyDrops() {
        assertThat(SparqlStoreClient.replaceGraphUpdate("http://h/a", "")).isEqualTo("DROP SILENT GRAPH <http://h/a> ;\n");
    }

    @Test
    void namespacePropertiesAreValidPropertiesXml() throws Exception {
        final java.util.Properties properties = new java.util.Properties();
        properties.loadFromXML(new java.io.ByteArrayInputStream(
            SparqlStoreClient.namespaceProperties("ns").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(properties.getProperty("com.bigdata.rdf.sail.namespace")).isEqualTo("ns");
        assertThat(properties.getProperty("com.bigdata.rdf.store.AbstractTripleStore.quads")).isEqualTo("true");
    }

    @Test
    void asksTheSparqlEndpoint() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/sparql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(Matchers.containsString("query=ASK")))
            .andRespond(withSuccess("{\"head\":{},\"boolean\":true}",
                MediaType.parseMediaType("application/sparql-results+json")));

        assertThat(client.ask("ASK { ?s ?p ?o }")).isTrue();
        server.verify();
    }

    @Test
    void countsNamedGraphs() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/sparql"))
            .andRespond(withSuccess("{\"head\":{\"vars\":[\"graphs\"]},\"results\":{\"bindings\":"
                + "[{\"graphs\":{\"type\":\"literal\",\"value\":\"5\"}}]}}",
                MediaType.parseMediaType("application/sparql-results+json")));

        assertThat(client.countGraphs()).isEqualTo(5L);
    }

    @Test
    void graphCountIsUnknownForUnexpectedResults() {
        server.expect(requestTo(SERVICE + "/namespace/articulorum/sparql"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.countGraphs()).isEqualTo(-1L);
    }

}
