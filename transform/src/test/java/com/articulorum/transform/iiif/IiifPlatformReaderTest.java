package com.articulorum.transform.iiif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests {@link IiifPlatformReader}.
 */
class IiifPlatformReaderTest {

    private final PublicContext context = new PublicContext("http://localhost:8000/transform/",
        "http://localhost:8000/platform/", "http://localhost:8000/ui/", "Bearer t");

    private MockRestServiceServer server;

    private IiifPlatformReader reader;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        reader = new IiifPlatformReader(builder, new IiifProperties());
    }

    @Test
    void readsWithPublicForwardedHeaders() {
        server.expect(requestTo("http://articulorum-platform/c/book"))
            .andExpect(header("X-Forwarded-Host", "localhost"))
            .andExpect(header("X-Forwarded-Port", "8000"))
            .andExpect(header("X-Forwarded-Prefix", "/platform"))
            .andExpect(header("Authorization", "Bearer t"))
            .andRespond(withSuccess("<http://localhost:8000/platform/c/book> <http://purl.org/dc/terms/title> \"B\" .\n",
                MediaType.parseMediaType("application/n-triples")));
        server.expect(requestTo("http://articulorum-platform/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://articulorum-platform/locked")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("http://articulorum-platform/img.png"))
            .andExpect(header("Range", "bytes=0-9"))
            .andRespond(withSuccess(new byte[10], MediaType.IMAGE_PNG));

        assertThat(reader.rdf("c/book", context)).hasValueSatisfying(model -> assertThat(model.size()).isEqualTo(1));
        assertThat(reader.rdf("missing", context)).isEmpty();
        assertThatThrownBy(() -> reader.rdf("locked", context))
            .isInstanceOfSatisfying(IiifException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
        assertThat(reader.head("img.png", 10, context)).hasValueSatisfying(bytes -> assertThat(bytes).hasSize(10));
    }

}
