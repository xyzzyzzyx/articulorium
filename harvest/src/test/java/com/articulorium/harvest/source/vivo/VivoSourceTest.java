package com.articulorium.harvest.source.vivo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestPage;
import com.articulorium.harvest.core.HarvestSource;
import com.articulorium.harvest.core.HarvestWindow;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.SourceCredentials;
import com.articulorium.harvest.source.Fixtures;
import com.articulorium.harvest.source.SourceRestClients;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * Tests the VIVO source against recorded SPARQL Query API responses.
 */
class VivoSourceTest {

    private static final String API = "https://vivo.test/vivo/api/sparqlQuery";

    private static final MediaType SPARQL_JSON = MediaType.parseMediaType("application/sparql-results+json");

    private MockRestServiceServer server;

    private VivoSourceFactory factory;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        factory = new VivoSourceFactory(new SourceRestClients(builder));
    }

    private HarvestSource source(final SourceCredentials credentials, final Map<String, String> options) {
        return factory.create(Fixtures.settings("vivo", "https://vivo.test/vivo/", 2, credentials, options));
    }

    /** Matches a form parameter of the request body. */
    private static RequestMatcher form(final String name, final java.util.function.Predicate<String> value) {
        return request -> {
            final String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
            final Map<String, String> form = Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair -> pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
            assertThat(form.get(name)).as("form parameter %s", name).matches(value);
        };
    }

    @Test
    void listsPeopleAndDescribesEachWithItsContext() {
        server.expect(requestTo(API))
            .andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andExpect(header(HttpHeaders.ACCEPT, "application/sparql-results+json"))
            .andExpect(form("email", "vivo_root@vivo.test"::equals))
            .andExpect(form("password", "secret"::equals))
            .andExpect(form("query", query -> query.contains("<http://xmlns.com/foaf/0.1/Person>")
                && !query.contains("FILTER(STR(?s) >") && query.endsWith("LIMIT 2")))
            .andRespond(withSuccess(Fixtures.bytes("vivo/people-page1.json"), SPARQL_JSON));
        server.expect(requestTo(API))
            .andExpect(form("query", query -> query.startsWith("CONSTRUCT")
                && query.contains("<https://vivo.test/individual/n1> ?p ?o")
                && query.contains("<http://vivoweb.org/ontology/core#relatedBy>")))
            .andRespond(withSuccess(Fixtures.bytes("vivo/n1.ttl"), MediaType.parseMediaType("text/turtle")));
        server.expect(requestTo(API))
            .andExpect(form("query", query -> query.contains("<https://vivo.test/individual/n2> ?p ?o")))
            .andRespond(withSuccess("", MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo(API))
            .andExpect(form("query", query -> query.contains("FILTER(STR(?s) > \"https://vivo.test/individual/n2\")")))
            .andRespond(withSuccess(Fixtures.bytes("vivo/people-page2.json"), SPARQL_JSON));
        final HarvestSource source = source(new SourceCredentials("vivo_root@vivo.test", "secret", null), Map.of());

        final HarvestPage first = source.fetch(HarvestWindow.ALL, null);
        final HarvestPage second = source.fetch(HarvestWindow.ALL, first.nextCursor());

        assertThat(source.capabilities().incremental()).isFalse();
        assertThat(first.records()).hasSize(2);
        final HarvestedRecord person = first.records().get(0);
        assertThat(person.identifier()).isEqualTo("https://vivo.test/individual/n1");
        assertThat(person.subject()).isEqualTo("https://vivo.test/individual/n1");
        assertThat(person.datestamp()).isNull();
        assertThat(person.metadata().size()).isEqualTo(10);
        assertThat(first.records().get(1).deleted()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("https://vivo.test/individual/n2");

        // an IRI that cannot be put in a query is reported, never queried
        assertThat(second.records()).isEmpty();
        assertThat(second.failures()).singleElement().satisfies(failure ->
            assertThat(failure.identifier()).isEqualTo("https://vivo.test/individual/n3>"));
        assertThat(second.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void theQueriesAreValidSparql() {
        final VivoSource source = (VivoSource) source(null,
            Map.of("classes", "http://xmlns.com/foaf/0.1/Person, http://xmlns.com/foaf/0.1/Organization"));

        QueryFactory.create(source.listQuery(null));
        QueryFactory.create(source.listQuery("https://vivo.test/individual/\"quoted\\"));
        QueryFactory.create(source.describeQuery("https://vivo.test/individual/n1"));
        assertThat(source.listQuery(null)).contains("<http://xmlns.com/foaf/0.1/Organization>");
    }

    @Test
    void sendsTokensAsBearerAndRefusedAccountsAreNotRetried() {
        server.expect(requestTo("https://sparql.test/query"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("Unauthorized"));
        final HarvestSource source = source(new SourceCredentials(null, null, "t"),
            Map.of("endpoint", "https://sparql.test/query"));

        assertThatThrownBy(() -> source.fetch(HarvestWindow.ALL, null))
            .isInstanceOfSatisfying(HarvestException.class, e -> {
                assertThat(e.isRecoverable()).isFalse();
                assertThat(e.getMessage()).contains("403");
            });
        server.verify();
    }

    @Test
    void rejectsClassesAndPredicatesThatAreNotIris() {
        assertThatThrownBy(() -> source(null, Map.of("classes", "foaf:Person")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("classes");
        assertThatThrownBy(() -> source(null, Map.of("context", "<http://x.test/a> } DROP ALL {")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(null, Map.of("context", " , ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
