package com.articulorium.harvest.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.harvest.service.HarvestService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Tests the {@code harvest} actuator endpoint over HTTP on the management port, with the sources of
 * the test configuration (an OAI-PMH source and a disabled VIVO source).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HarvestEndpointTest {

    @LocalManagementPort
    private int port;

    @Autowired
    private HarvestService harvests;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port + "/actuator/harvest")
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();
    }

    @Test
    void listsTheConfiguredSourcesWithoutCredentials() {
        final ResponseEntity<String> response = client.get().retrieve().toEntity(String.class);
        final JsonNode body = client.get().retrieve().body(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).doesNotContain("secret");
        assertThat(body.path("sources")).hasSize(2);
        final JsonNode repo = body.path("sources").get(0);
        assertThat(repo.path("id").asString()).isEqualTo("repo");
        assertThat(repo.path("type").asString()).isEqualTo("oai-pmh");
        assertThat(repo.path("url").asString()).isEqualTo("https://repo.test/oai");
        assertThat(repo.path("schedule").asString()).isEqualTo("0 0 3 * * *");
        assertThat(repo.path("capabilities").path("incremental").asBoolean()).isTrue();
        assertThat(body.path("sources").get(1).path("enabled").asBoolean()).isFalse();
        assertThat(harvests.statuses()).hasSize(2);
    }

    @Test
    void showsOneSource() {
        assertThat(client.get().uri("/profiles").retrieve().body(JsonNode.class).path("type").asString())
            .isEqualTo("vivo");
        assertThat(client.get().uri("/nope").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void refusesHarvestsOfUnknownOrDisabledSourcesAndUnknownModes() {
        assertThat(client.post().uri("/nope").contentType(MediaType.APPLICATION_JSON).body("{}").retrieve()
            .toBodilessEntity().getStatusCode().value()).isEqualTo(404);
        final ResponseEntity<JsonNode> disabled = client.post().uri("/profiles").contentType(MediaType.APPLICATION_JSON)
            .body("{\"mode\":\"full\"}").retrieve().toEntity(JsonNode.class);
        assertThat(disabled.getStatusCode().value()).isEqualTo(409);
        assertThat(disabled.getBody().path("error").asString()).contains("disabled");
        assertThat(client.post().uri("/repo").contentType(MediaType.APPLICATION_JSON).body("{\"mode\":\"sideways\"}")
            .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(400);
        assertThat(client.delete().uri("/repo").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(409);
    }

}
