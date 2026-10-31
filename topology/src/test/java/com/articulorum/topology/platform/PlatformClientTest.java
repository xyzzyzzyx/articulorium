package com.articulorum.topology.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.List;

import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.topology.config.TopologyProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests {@link PlatformClient} against a mocked platform.
 */
class PlatformClientTest {

    private MockRestServiceServer server;

    private PlatformClient client;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        final TopologyProperties properties = new TopologyProperties();
        properties.setPlatformUrl("http://articulorum-platform");
        client = new PlatformClient(builder, properties);
    }

    @Test
    void createsWithSlugTypeLinkAuthorizationAndForwardedHeaders() {
        server.expect(requestTo("http://articulorum-platform/books"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Slug", "moby"))
            .andExpect(header("Link", "<" + Ldp.BASIC_CONTAINER + ">; rel=\"type\""))
            .andExpect(header("Authorization", "Bearer t"))
            .andExpect(header("X-Forwarded-Host", "articulorum-platform"))
            .andExpect(header("X-Forwarded-Port", "80"))
            .andExpect(headerDoesNotExist("X-Forwarded-Prefix"))
            .andExpect(content().contentTypeCompatibleWith("text/turtle"))
            .andRespond(withCreatedEntity(URI.create("http://articulorum-platform/books/moby")));

        assertThat(client.create("books", "moby", "<> a <x:y> .", List.of(Ldp.BASIC_CONTAINER), "Bearer t"))
            .isEqualTo("books/moby");
        server.verify();
    }

    @Test
    void readsRdfAndBytesAndTreatsMissingResourcesAsEmpty() {
        server.expect(requestTo("http://articulorum-platform/a"))
            .andExpect(header("Accept", "application/n-triples"))
            .andRespond(withSuccess("<http://articulorum-platform/a> <http://purl.org/dc/terms/title> \"A\" .\n",
                MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo("http://articulorum-platform/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://articulorum-platform/t.json")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.rdf("a", null)).hasValueSatisfying(model -> assertThat(model.size()).isEqualTo(1));
        assertThat(client.bytes("missing", null)).isEmpty();
        assertThat(client.bytes("t.json", null)).hasValueSatisfying(bytes -> assertThat(bytes).hasSize(2));
    }

    @Test
    void refusalsKeepTheirStatus() {
        server.expect(requestTo("http://articulorum-platform/locked")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("http://articulorum-platform/broken")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.delete("locked", null))
            .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
        assertThatThrownBy(() -> client.bytes("broken", null))
            .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.getStatus()).isEqualTo(502));
    }

    @Test
    void forwardedHeadersDescribeTheBase() {
        final var headers = PlatformClient.forwardedHeaders(URI.create("https://gateway.example:8443/platform/"));
        assertThat(headers.getFirst("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(headers.getFirst("X-Forwarded-Host")).isEqualTo("gateway.example");
        assertThat(headers.getFirst("X-Forwarded-Port")).isEqualTo("8443");
        assertThat(headers.getFirst("X-Forwarded-Prefix")).isEqualTo("/platform");
        assertThat(client.pathOf(URI.create("http://articulorum-platform/a/b%20c"))).isEqualTo("a/b%20c");
    }

}
