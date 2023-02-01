package com.articulorium.oai.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import com.articulorium.oai.config.OaiProperties;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests {@link PlatformClient} against a mocked platform.
 */
class PlatformClientTest {

    private static final PlatformView VIEW = new PlatformView("http://localhost:8000/platform/", "Bearer t");

    private MockRestServiceServer server;

    private PlatformClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final OaiProperties properties = new OaiProperties();
        properties.setPlatformUrl("http://articulorium-platform");
        client = new PlatformClient(builder, properties);
    }

    @Test
    void searchesOrderedByModificationWithForwardedHeadersAndCredentials() {
        server.expect(requestTo(Matchers.startsWith("http://articulorium-platform/fcr:search?")))
            .andExpect(queryParam("order_by", "modified"))
            .andExpect(queryParam("order", "asc"))
            .andExpect(queryParam("max_results", "50"))
            .andExpect(queryParam("offset", "7"))
            .andExpect(queryParam("condition", "rdf_type%3Dhttp%3A%2F%2Fpcdm.org%2Fmodels%23Object",
                "modified%3E%3D2026-01-01T00%3A00%3A00Z"))
            .andExpect(header("Authorization", "Bearer t"))
            .andExpect(header("X-Forwarded-Host", "localhost"))
            .andExpect(header("X-Forwarded-Port", "8000"))
            .andExpect(header("X-Forwarded-Proto", "http"))
            .andExpect(header("X-Forwarded-Prefix", "/platform"))
            .andRespond(withSuccess("""
                {"items": [
                  {"fedora_id": "http://localhost:8000/platform/books/moby", "modified": "2026-11-05T18:30:15.123456Z",
                   "rdf_type": ["http://pcdm.org/models#Object", "http://www.w3.org/ns/ldp#BasicContainer"]},
                  {"fedora_id": "http://elsewhere/x", "modified": "2026-11-05T18:30:15Z", "rdf_type": []},
                  {"fedora_id": "http://localhost:8000/platform/books/old", "modified": 1762367415.5, "rdf_type": []}
                 ],
                 "pagination": {"maxResults": 40, "offset": 7, "totalResults": 120, "pageNumber": 0, "numberOfPages": 3}}
                """, MediaType.APPLICATION_JSON));

        final SearchPage page = client.search(VIEW, List.of(Conditions.type("http://pcdm.org/models#Object"),
            Conditions.modifiedFrom(Instant.parse("2026-01-01T00:00:00Z"))), 50, 7);

        assertThat(page.total()).isEqualTo(120);
        assertThat(page.maxResults()).isEqualTo(40);
        assertThat(page.covered(7)).isEqualTo(33);
        assertThat(page.hits()).extracting(SearchHit::path).containsExactly("books/moby", "books/old");
        assertThat(page.hits().getFirst().modified()).isEqualTo(Instant.parse("2026-11-05T18:30:15.123456Z"));
        assertThat(page.hits().getFirst().hasType("http://pcdm.org/models#Object")).isTrue();
        assertThat(page.hits().get(1).modified()).isEqualTo(Instant.ofEpochSecond(1762367415L, 500_000_000L));
        server.verify();
    }

    @Test
    void readsRdfWithPublicIrisAndTreatsMissingOrForbiddenResourcesAsAbsent() {
        server.expect(requestTo("http://articulorium-platform/books/moby"))
            .andExpect(header("Accept", "application/n-triples"))
            .andRespond(withSuccess("<http://localhost:8000/platform/books/moby> <http://purl.org/dc/terms/title> "
                + "\"Moby-Dick\" .\n", MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo("http://articulorium-platform/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://articulorium-platform/secret")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.rdf(VIEW, "books/moby")).hasValueSatisfying(model -> assertThat(model.contains(
            model.createResource("http://localhost:8000/platform/books/moby"),
            model.createProperty("http://purl.org/dc/terms/title"))).isTrue());
        assertThat(client.rdf(VIEW, "gone")).isEmpty();
        assertThat(client.rdf(VIEW, "secret")).isEmpty();
        server.verify();
    }

    @Test
    void reportsPlatformFailures() {
        server.expect(requestTo(Matchers.startsWith("http://articulorium-platform/fcr:search")))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withServerError());
        server.expect(requestTo(Matchers.startsWith("http://articulorium-platform/fcr:search")))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Unknown search field"));
        final PlatformView anonymous = new PlatformView("http://localhost:8000/platform", null);

        assertThatThrownBy(() -> client.search(anonymous, List.of(), 10, 0))
            .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.getStatus()).isEqualTo(502));
        assertThatThrownBy(() -> client.search(anonymous, List.of(), 10, 0))
            .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.getStatus()).isEqualTo(400))
            .hasMessageContaining("Unknown search field");
    }

    @Test
    void escapesWildcardsInConditionValues() {
        assertThat(Conditions.path("/a*b/c\\d/")).isEqualTo("fedora_id=info:fedora/a\\*b/c\\\\d");
        assertThat(Conditions.below("communities/arts")).isEqualTo("fedora_id=info:fedora/communities/arts/*");
        assertThat(Conditions.modifiedBefore(Instant.parse("2026-01-02T00:00:00Z")))
            .isEqualTo("modified<2026-01-02T00:00:00Z");
    }

}
