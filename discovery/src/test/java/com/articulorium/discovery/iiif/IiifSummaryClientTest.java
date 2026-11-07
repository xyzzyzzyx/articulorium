package com.articulorium.discovery.iiif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.config.PublicUrls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests the summaries of IIIF exports against a mocked transform service.
 */
class IiifSummaryClientTest {

    private static final String MANIFEST = """
        {"@context": "http://iiif.io/api/presentation/3/context.json",
         "id": "http://localhost:8000/transform/iiif/c/book", "type": "Manifest",
         "thumbnail": [{"id": "http://localhost:8000/platform/c/book/members/page-1/files/master.png", "type": "Image"}],
         "rendering": [{"id": "http://localhost:8000/platform/c/book/files/book.pdf", "type": "Text", "format": "application/pdf"}],
         "items": [
           {"type": "Canvas", "items": [{"type": "AnnotationPage", "items": [{"body": {"type": "Image"}}]}]},
           {"type": "Canvas", "items": [{"type": "AnnotationPage", "items": [{"body": {"type": "Video"}}]}]},
           {"type": "Canvas", "items": [{"type": "AnnotationPage", "items": [{"body": {"type": "Text", "format": "application/pdf"}}]}],
            "rendering": [{"type": "Text", "format": "text/plain"}]},
           {"type": "Canvas", "items": [{"type": "AnnotationPage", "items": [{"body": {"type": "Sound"}}]}]}
         ]}
        """;

    private MockRestServiceServer transform;

    private IiifSummaryClient client;

    private DiscoveryProperties properties;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder();
        transform = MockRestServiceServer.bindTo(builder).build();
        properties = new DiscoveryProperties();
        properties.setTransformUrl("http://transform.test");
        client = new IiifSummaryClient(builder, properties, new PublicUrls(properties),
            Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void summarizesManifestsReadAtThePublicBase() {
        transform.expect(requestTo("http://transform.test/iiif/c/book"))
            .andExpect(header("X-Forwarded-Host", "localhost"))
            .andExpect(header("X-Forwarded-Prefix", "/transform"))
            .andExpect(header("Authorization", "Bearer alice"))
            .andRespond(withSuccess(MANIFEST, MediaType.APPLICATION_JSON));

        assertThat(client.summary("c/book", "Bearer alice")).hasValue(new IiifSummary(
            "http://localhost:8000/transform/iiif/c/book", "Manifest",
            "http://localhost:8000/platform/c/book/members/page-1/files/master.png", 4, 1, 1, 1, 2, 0));
    }

    @Test
    void remembersAnonymousSummaries() {
        transform.expect(once(), requestTo("http://transform.test/iiif/c/book"))
            .andRespond(withSuccess(MANIFEST, MediaType.APPLICATION_JSON));

        assertThat(client.summary("c/book", null)).isPresent();
        assertThat(client.summary("c/book", null)).isPresent();
        transform.verify();
    }

    @Test
    void summarizesCollections() {
        final IiifSummary summary = IiifSummaryClient.summarize(tools.jackson.databind.json.JsonMapper.shared().readTree("""
            {"type": "Collection", "items": [{"id": "a", "type": "Manifest"}, {"id": "b", "type": "Collection"}]}
            """), "http://x/iiif/c");

        assertThat(summary).isEqualTo(new IiifSummary("http://x/iiif/c", "Collection", null, 0, 0, 0, 0, 0, 2));
        assertThat(summary.viewable()).isTrue();
    }

    @Test
    void hasNoSummaryForResourcesWithoutExport() {
        transform.expect(requestTo("http://transform.test/iiif/plain")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(client.summary("plain", "Bearer x")).isEmpty();
    }

    @Test
    void readsNothingWhenTurnedOff() {
        properties.getIiif().setEnabled(false);

        assertThat(client.summary("c/book", null)).isEmpty();
        transform.verify();
    }

}
