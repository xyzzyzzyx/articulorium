package com.articulorium.harvest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.articulorium.harvest.core.HarvestMode;
import com.articulorium.harvest.core.HarvestOutcome;
import com.articulorium.harvest.core.HarvestReport;
import com.articulorium.harvest.core.RecordFailure;
import com.articulorium.harvest.core.SourceCapabilities;
import com.articulorium.harvest.service.HarvestRequestException;
import com.articulorium.harvest.service.HarvestRequestException.Reason;
import com.articulorium.harvest.service.HarvestService;
import com.articulorium.harvest.service.SourceStatus;
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
 * Tests the harvest API over HTTP on the service port, with the access check and the harvest
 * service mocked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HarvestControllerTest {

    private static final Instant STARTED = Instant.parse("2026-11-06T10:00:00Z");

    private static final Caller OPERATOR = new Caller("info:oidc/agent/alice", true, Set.of("read", "write"));

    private static final Caller READER = new Caller("info:oidc/agent/bob", true, Set.of("read"));

    private static final HarvestReport REPORT = new HarvestReport("repo", "oai-pmh", HarvestMode.INCREMENTAL,
        HarvestOutcome.COMPLETED_WITH_ERRORS, STARTED, STARTED.plusSeconds(42), STARTED.minusSeconds(86_400), null,
        3, 120, 100, 15, 3, 0, 2, List.of(new RecordFailure("oai:repo:7", "Unmappable metadata")), null);

    private static final SourceStatus REPO = new SourceStatus("repo", "oai-pmh", "https://repo.test/oai", true,
        "0 0 3 * * *", new SourceCapabilities(true, true), null, List.of(REPORT));

    private static final SourceStatus RUNNING = new SourceStatus("repo", "oai-pmh", "https://repo.test/oai", true,
        "0 0 3 * * *", new SourceCapabilities(true, true),
        new SourceStatus.Running(HarvestMode.FULL, STARTED, 0, 0, false), List.of(REPORT));

    @LocalServerPort
    private int port;

    @MockitoBean
    private HarvestAccess access;

    @MockitoBean
    private HarvestService harvests;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port + "/sources")
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();
        when(access.require(any(), eq(Operation.VIEW))).thenReturn(OPERATOR);
        when(access.require(any(), eq(Operation.OPERATE))).thenReturn(OPERATOR);
    }

    @Test
    void listsTheSourcesWithTheirReportsAndWhetherTheCallerMayOperate() {
        when(access.require("Bearer bob", Operation.VIEW)).thenReturn(READER);
        when(harvests.statuses()).thenReturn(List.of(REPO));

        final JsonNode body = client.get().header("Authorization", "Bearer bob").retrieve().body(JsonNode.class);

        assertThat(body.path("operate").asBoolean(true)).isFalse();
        final JsonNode source = body.path("sources").get(0);
        assertThat(source.path("id").asString()).isEqualTo("repo");
        assertThat(source.path("capabilities").path("incremental").asBoolean()).isTrue();
        assertThat(source.path("capabilities").path("deletions").asBoolean()).isTrue();
        final JsonNode report = source.path("reports").get(0);
        assertThat(report.path("outcome").asString()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(report.path("mode").asString()).isEqualTo("INCREMENTAL");
        assertThat(report.path("startedAt").asString()).isEqualTo("2026-11-06T10:00:00Z");
        assertThat(report.path("finishedAt").asString()).isEqualTo("2026-11-06T10:00:42Z");
        assertThat(report.path("created").asLong()).isEqualTo(100);
        assertThat(report.path("failures").get(0).path("identifier").asString()).isEqualTo("oai:repo:7");
    }

    @Test
    void tellsOperatorsTheyMayOperate() {
        when(harvests.statuses()).thenReturn(List.of());

        final JsonNode body = client.get().header("Authorization", "Bearer alice").retrieve().body(JsonNode.class);

        assertThat(body.path("operate").asBoolean()).isTrue();
        assertThat(body.path("sources")).isEmpty();
    }

    @Test
    void showsOneSourceOr404() {
        when(harvests.status("repo")).thenReturn(Optional.of(REPO));
        when(harvests.status("nope")).thenReturn(Optional.empty());

        assertThat(client.get().uri("/repo").retrieve().body(JsonNode.class).path("type").asString()).isEqualTo("oai-pmh");
        final ResponseEntity<JsonNode> missing = client.get().uri("/nope").retrieve().toEntity(JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().path("detail").asString()).isEqualTo("No harvest source nope");
    }

    @Test
    void startsHarvestsInTheRequestedMode() {
        when(harvests.start("repo", HarvestMode.FULL)).thenReturn(RUNNING);

        final ResponseEntity<JsonNode> response = client.post().uri("/repo/harvests")
            .header("Authorization", "Bearer alice").contentType(MediaType.APPLICATION_JSON)
            .body("{\"mode\":\"full\"}").retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().path("running").path("mode").asString()).isEqualTo("FULL");
        verify(access).require("Bearer alice", Operation.OPERATE);
    }

    @Test
    void startsIncrementalHarvestsWithoutAMode() {
        when(harvests.start("repo", HarvestMode.INCREMENTAL)).thenReturn(RUNNING);

        assertThat(client.post().uri("/repo/harvests").contentType(MediaType.APPLICATION_JSON).body("{}").retrieve()
            .toBodilessEntity().getStatusCode().value()).isEqualTo(202);
        verify(harvests).start("repo", HarvestMode.INCREMENTAL);
    }

    @Test
    void refusesUnknownModesAndUnreadableBodies() {
        final ResponseEntity<JsonNode> sideways = client.post().uri("/repo/harvests")
            .contentType(MediaType.APPLICATION_JSON).body("{\"mode\":\"sideways\"}").retrieve().toEntity(JsonNode.class);
        assertThat(sideways.getStatusCode().value()).isEqualTo(400);
        assertThat(sideways.getBody().path("detail").asString()).contains("incremental or full");
        assertThat(client.post().uri("/repo/harvests").contentType(MediaType.APPLICATION_JSON).body("{nope")
            .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(400);
        verify(harvests, never()).start(any(), any());
    }

    @Test
    void reportsConflictsAsProblems() {
        when(harvests.start("repo", HarvestMode.INCREMENTAL))
            .thenThrow(new HarvestRequestException(Reason.RUNNING, "Harvest source repo is being harvested"));
        when(harvests.cancel("repo"))
            .thenThrow(new HarvestRequestException(Reason.NOT_RUNNING, "Harvest source repo is not being harvested"));

        final ResponseEntity<JsonNode> busy = client.post().uri("/repo/harvests").contentType(MediaType.APPLICATION_JSON)
            .body("{}").retrieve().toEntity(JsonNode.class);
        final ResponseEntity<JsonNode> idle = client.delete().uri("/repo/harvests/running").retrieve()
            .toEntity(JsonNode.class);

        assertThat(busy.getStatusCode().value()).isEqualTo(409);
        assertThat(busy.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(busy.getHeaders().getFirst("Content-Language")).isEqualTo("en");
        assertThat(busy.getBody().path("reason").asString()).isEqualTo("RUNNING");
        assertThat(busy.getBody().path("title").asString()).isEqualTo("Conflict");
        assertThat(idle.getStatusCode().value()).isEqualTo(409);
        assertThat(idle.getBody().path("reason").asString()).isEqualTo("NOT_RUNNING");
    }

    @Test
    void cancelsRunningHarvests() {
        when(harvests.cancel("repo")).thenReturn(RUNNING);

        assertThat(client.delete().uri("/repo/harvests/running").header("Authorization", "Bearer alice").retrieve()
            .toBodilessEntity().getStatusCode().value()).isEqualTo(202);
        verify(harvests).cancel("repo");
    }

    @Test
    void refusesCallersWithoutAccessBeforeTouchingAnySource() {
        when(access.require(isNull(), eq(Operation.VIEW))).thenThrow(new HarvestAccessException(
            HarvestAccessException.Reason.UNAUTHENTICATED, "Harvests need read access to /harvested"));
        when(access.require("Bearer bob", Operation.OPERATE)).thenThrow(new HarvestAccessException(
            HarvestAccessException.Reason.FORBIDDEN, "Harvests need write access to /harvested"));

        final ResponseEntity<JsonNode> anonymous = client.get().retrieve().toEntity(JsonNode.class);
        final ResponseEntity<JsonNode> reader = client.post().uri("/repo/harvests").header("Authorization", "Bearer bob")
            .contentType(MediaType.APPLICATION_JSON).body("{\"mode\":\"full\"}").retrieve().toEntity(JsonNode.class);
        final ResponseEntity<JsonNode> canceller = client.delete().uri("/repo/harvests/running")
            .header("Authorization", "Bearer bob").retrieve().toEntity(JsonNode.class);

        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(reader.getStatusCode().value()).isEqualTo(403);
        assertThat(reader.getBody().path("detail").asString()).contains("write access");
        assertThat(canceller.getStatusCode().value()).isEqualTo(403);
        verify(harvests, never()).statuses();
        verify(harvests, never()).start(any(), any());
        verify(harvests, never()).cancel(any());
    }

    @Test
    void reportsAnUndecidableAccessAsUnavailable() {
        when(access.require(isNull(), eq(Operation.VIEW))).thenThrow(new HarvestAccessException(
            HarvestAccessException.Reason.UNAVAILABLE, "Access to harvests cannot be checked: the platform is unavailable"));

        assertThat(client.get().retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void servesNoReviewQueueWithoutClassification() {
        when(harvests.statuses()).thenReturn(List.of(REPO));

        assertThat(client.get().retrieve().body(JsonNode.class).path("classification").asBoolean()).isFalse();
        final ResponseEntity<JsonNode> queue = RestClient.builder().baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build()
            .get().uri("/classifications").retrieve().toEntity(JsonNode.class);
        assertThat(queue.getStatusCode().value()).isEqualTo(404);
    }

}
