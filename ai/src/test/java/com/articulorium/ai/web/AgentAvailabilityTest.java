package com.articulorium.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.agent.AgentRegistry;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Tests the agent when it is turned off, when one capability is turned off and when no chat model
 * is configured: it says so and answers every request with a clear problem, without a model call.
 */
class AgentAvailabilityTest {

    private static ResponseEntity<JsonNode> post(final int port, final String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build().post().uri(path).contentType(MediaType.APPLICATION_JSON).body("{\"path\": \"communities/x\"}")
            .retrieve().toEntity(JsonNode.class);
    }

    private static JsonNode capabilities(final int port) {
        return RestClient.create("http://localhost:" + port).get().uri("/capabilities").retrieve().body(JsonNode.class);
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "articulorium.ai.enabled=false")
    class TurnedOff {

        @LocalServerPort
        private int port;

        @Autowired
        private AgentRegistry agents;

        @Test
        void saysItIsOffAndRefusesEveryRequest() {
            final JsonNode capabilities = capabilities(port);
            assertThat(capabilities.path("enabled").asBoolean()).isFalse();
            assertThat(capabilities.path("capabilities").path("topics").asBoolean()).isFalse();

            final ResponseEntity<JsonNode> response = post(port, "/topic-models");
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().path("reason").asString()).isEqualTo("DISABLED");
            assertThat(agents.getNames()).noneMatch(name -> name.startsWith("ai-"));
        }

    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    class WithoutModel {

        @LocalServerPort
        private int port;

        @Test
        void saysThereIsNoModelAndRefusesEveryRequest() {
            final JsonNode capabilities = capabilities(port);
            assertThat(capabilities.path("enabled").asBoolean()).isTrue();
            assertThat(capabilities.path("model").asBoolean()).isFalse();
            assertThat(capabilities.path("modelName").isNull()).isTrue();
            assertThat(capabilities.path("capabilities").path("media").asBoolean()).isFalse();

            final ResponseEntity<JsonNode> response = post(port, "/media-metadata");
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().path("reason").asString()).isEqualTo("MODEL_UNAVAILABLE");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        }

    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "articulorium.ai.media.enabled=false",
        classes = {com.articulorium.ai.AiApplication.class, AgentControllerTest.Beans.class})
    class CapabilityTurnedOff {

        @LocalServerPort
        private int port;

        @Autowired
        private AgentRegistry agents;

        @Test
        void offersTheOtherCapabilities() {
            final JsonNode capabilities = capabilities(port);
            assertThat(capabilities.path("capabilities").path("media").asBoolean()).isFalse();
            assertThat(capabilities.path("capabilities").path("topics").asBoolean()).isTrue();

            final ResponseEntity<JsonNode> response = post(port, "/media-metadata");
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().path("reason").asString()).isEqualTo("CAPABILITY_DISABLED");
            assertThat(agents.getNames()).contains("ai-topic-modeler", "ai-profile-enhancer")
                .doesNotContain("ai-media-describer");
        }

    }

}
