package com.articulorium.harvest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.articulorium.harvest.classify.Classification;
import com.articulorium.harvest.classify.ReviewException;
import com.articulorium.harvest.classify.ReviewService;
import com.articulorium.harvest.classify.Suggestion;
import com.articulorium.harvest.service.HarvestService;
import com.articulorium.harvest.web.HarvestAccess.Caller;
import com.articulorium.harvest.web.HarvestAccess.Operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Tests the review queue's API over HTTP with classification enabled, the access check and the
 * review service mocked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "articulorium.harvest.classification.enabled=true")
class ReviewControllerTest {

    private static final Caller OPERATOR = new Caller("info:oidc/agent/alice", true, Set.of("read", "write"));

    private static final Caller READER = new Caller("info:oidc/agent/bob", true, Set.of("read"));

    private static final Classification PENDING = new Classification("repo", "harvested/repo/r1", "oai:repo:1",
        "Quantum dots", Classification.Status.PENDING, new Suggestion(Suggestion.EXISTING_COLLECTION,
            new Suggestion.Ref("communities/science/theses", "Theses", "Science > Theses"), null, null, null,
            new Suggestion.Topology("EXISTING", "thesis", "Thesis", null), 0.42, "A thesis.", List.of(), "ollama:x"),
        null, true, Instant.parse("2026-11-07T10:00:00Z"), null, null, null);

    @LocalServerPort
    private int port;

    @MockitoBean
    private HarvestAccess access;

    @MockitoBean
    private ReviewService reviews;

    @MockitoBean
    private HarvestService harvests;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();
    }

    @Test
    void listsTheQueueWithWhetherTheCallerMayDecide() {
        when(access.require(any(), eq(Operation.VIEW))).thenReturn(READER);
        when(reviews.list("Bearer bob", "repo", true, 0, 25)).thenReturn(new ReviewService.Page(List.of(PENDING), 1, 0));

        final JsonNode queue = client.get().uri("/classifications?source=repo").header("Authorization", "Bearer bob")
            .retrieve().body(JsonNode.class);

        assertThat(queue.path("total").asLong()).isEqualTo(1);
        assertThat(queue.path("decide").asBoolean()).isFalse();
        final JsonNode first = queue.path("classifications").get(0);
        assertThat(first.path("status").asString()).isEqualTo("PENDING");
        assertThat(first.path("uncertain").asBoolean()).isTrue();
        assertThat(first.path("suggestion").path("collection").path("path").asString())
            .isEqualTo("communities/science/theses");
        assertThat(first.path("classifiedAt").asString()).isEqualTo("2026-11-07T10:00:00Z");
    }

    @Test
    void acceptsWithTheCallersCredentialsAndChoices() {
        when(access.require(any(), eq(Operation.OPERATE))).thenReturn(OPERATOR);
        when(reviews.accept("Bearer alice", "info:oidc/agent/alice", "repo", "r1", "communities/other", "none"))
            .thenReturn(PENDING);

        final ResponseEntity<JsonNode> response = client.post().uri("/classifications/repo/r1/acceptance")
            .header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON)
            .body("{\"collection\": \"communities/other\", \"topology\": \"none\"}").retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void refusesReadersToDecide() {
        when(access.require(any(), eq(Operation.OPERATE))).thenThrow(new HarvestAccessException(
            HarvestAccessException.Reason.FORBIDDEN, "Harvests need write access to /harvested"));

        final ResponseEntity<JsonNode> response = client.post().uri("/classifications/repo/r1/rejection")
            .header("Authorization", "Bearer bob").retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(reviews, never()).reject(any(), any(), any(), any());
    }

    @Test
    void explainsRefusalsWithAReason() {
        when(access.require(any(), eq(Operation.OPERATE))).thenReturn(OPERATOR);
        when(reviews.accept(any(), any(), eq("repo"), eq("r1"), any(), any())).thenThrow(new ReviewException(409,
            ReviewException.TARGET_REQUIRED, "The suggestion names no existing collection"));

        final ResponseEntity<JsonNode> response = client.post().uri("/classifications/repo/r1/acceptance")
            .header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON).body("{}")
            .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().path("reason").asString()).isEqualTo("TARGET_REQUIRED");
        assertThat(response.getHeaders().getFirst("Content-Language")).isEqualTo("en");
    }

    @Test
    void retriesAClassification() {
        when(access.require(any(), eq(Operation.OPERATE))).thenReturn(OPERATOR);
        when(reviews.retry("Bearer alice", "repo", "r1")).thenReturn(PENDING);

        assertThat(client.post().uri("/classifications/repo/r1/retry").header("Authorization", "Bearer alice")
            .retrieve().toEntity(JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void tellsTheUserInterfaceThatRecordsAreClassified() {
        when(access.require(any(), eq(Operation.VIEW))).thenReturn(OPERATOR);
        when(harvests.statuses()).thenReturn(List.of());

        final JsonNode sources = client.get().uri("/sources").retrieve().body(JsonNode.class);
        assertThat(sources.path("classification").asBoolean()).isTrue();
        // automatic acceptance is the default; a source that is not classified is not listed
        assertThat(sources.path("autoAccepted").valueStream().map(JsonNode::asString).toList())
            .containsExactly("repo");
    }

}
