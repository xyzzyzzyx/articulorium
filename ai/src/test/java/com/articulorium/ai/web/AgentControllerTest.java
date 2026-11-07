package com.articulorium.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.agent.AgentRegistry;
import com.articulorium.ai.support.Files;
import com.articulorium.ai.support.Platform;
import com.articulorium.ai.support.ScriptedChatModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Tests the agent API over HTTP, with Spring AI's auto-configured chat client in front of a
 * scripted chat model and a mocked platform.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AgentControllerTest {

    private static final String BINARY = "photos/harbour.jpg";

    /** The test's chat model and HTTP mocks. */
    @TestConfiguration
    static class Beans {

        static Platform platform;

        @Bean
        ScriptedChatModel chatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            final RestClient.Builder builder = RestClient.builder();
            platform = Platform.bindTo(builder);
            return builder;
        }

    }

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedChatModel chat;

    @Autowired
    private AgentRegistry agents;

    private RestClient client;

    @BeforeEach
    void setUp() {
        chat.reset();
        Beans.platform.server().reset();
        client = RestClient.builder().baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();
    }

    private void binary() {
        final byte[] jpeg = Files.image("jpeg", 20, 10);
        Beans.platform.binary(BINARY, "image/jpeg", jpeg, """
            <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
            <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "image/jpeg" .
            """).rdf("photos", "<> <http://purl.org/dc/terms/title> \"Photographs\" .\n");
    }

    private ResponseEntity<JsonNode> post(final String path, final String body, final String authorization,
            final String language) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> {
                if (authorization != null) {
                    headers.set("Authorization", authorization);
                }
                headers.set("Accept-Language", language);
            })
            .body(body).retrieve().toEntity(JsonNode.class);
    }

    @Test
    void tellsWhatItOffers() {
        final JsonNode capabilities = client.get().uri("/capabilities").retrieve().body(JsonNode.class);

        assertThat(capabilities.path("enabled").asBoolean()).isTrue();
        assertThat(capabilities.path("model").asBoolean()).isTrue();
        assertThat(capabilities.path("modelName").asString()).startsWith("scripted");
        assertThat(capabilities.path("capabilities").path("media").asBoolean()).isTrue();
        assertThat(capabilities.path("capabilities").path("classification").asBoolean()).isTrue();
        assertThat(capabilities.path("capabilities").path("profiles").asBoolean()).isTrue();
        // the capabilities are the agents of the registry, listed once
        assertThat(agents.getNames()).containsSubsequence("ai-topology-assistant", "ai-media-describer",
            "ai-topic-modeler", "ai-profile-enhancer", "ai-harvest-classifier");
    }

    @Test
    void suggestsForAProfileInTheCallersLanguage() {
        Beans.platform.access("profiles/curie", true, "read", "write").rdf("profiles/curie", """
            <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://xmlns.com/foaf/0.1/Person> .
            <> <http://vivoweb.org/ontology/core#relatedBy> <http://platform.test/profiles/curie#authorship-1> .
            <http://platform.test/profiles/curie#authorship-1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://vivoweb.org/ontology/core#Authorship> .
            <http://platform.test/profiles/curie#authorship-1> <http://vivoweb.org/ontology/core#relates> <http://platform.test/profiles/curie#publication-1> .
            <http://platform.test/profiles/curie#publication-1> <http://www.w3.org/2000/01/rdf-schema#label> "Radioactive substances" .
            """);
        chat.answer("{\"overview\": \"Étudie la radioactivité.\", \"researchAreas\": [\"Radiochimie\"], \"keywords\": []}");

        final ResponseEntity<JsonNode> response = post("/profile-suggestions", "{\"path\": \"profiles/curie\"}",
            Platform.TOKEN, "fr");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("overview").asString()).isEqualTo("Étudie la radioactivité.");
        assertThat(response.getBody().path("researchAreas").get(0).asString()).isEqualTo("Radiochimie");
        assertThat(response.getBody().path("basedOn").path("publications").asInt()).isEqualTo(1);
        assertThat(chat.prompts().get(0).getSystemMessage().getText()).contains("French (fr)");
    }

    @Test
    void suggestsMediaMetadataInTheCallersLanguage() {
        Beans.platform.access(BINARY, true, "read", "write");
        binary();
        chat.answer("{\"title\": \"Hafen\", \"description\": \"Schiffe am Kai.\"}");

        final ResponseEntity<JsonNode> response = post("/media-metadata", "{\"path\": \"" + BINARY + "\"}",
            Platform.TOKEN, "de");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("kind").asString()).isEqualTo("IMAGE");
        assertThat(response.getBody().path("statements").findValuesAsString("value")).contains("20", "10", "Hafen",
            "Schiffe am Kai.");
        assertThat(chat.prompts().get(0).getSystemMessage().getText()).contains("German (de)");
        assertThat(chat.prompts().get(0).getUserMessage().getMedia()).hasSize(1);
    }

    @Test
    void refusesReadersWithAProblem() {
        Beans.platform.access(BINARY, true, "read");

        final ResponseEntity<JsonNode> response = post("/media-metadata", "{\"path\": \"" + BINARY + "\"}",
            Platform.TOKEN, "en");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(response.getBody().path("reason").asString()).isEqualTo("FORBIDDEN");
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isNull();
        assertThat(chat.prompts()).isEmpty();
    }

    @Test
    void asksAnonymousCallersToSignIn() {
        Beans.platform.access(BINARY, false, "read");

        final ResponseEntity<JsonNode> response = post("/media-metadata", "{\"path\": \"" + BINARY + "\"}", null, "en");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().path("reason").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void reportsAFailingModelWithoutBreakingTheApi() {
        Beans.platform.access(BINARY, true, "write");
        binary();
        chat.answer(new IllegalStateException("upstream timeout"));

        final ResponseEntity<JsonNode> failed = post("/media-metadata", "{\"path\": \"" + BINARY + "\"}",
            Platform.TOKEN, "en");

        assertThat(failed.getStatusCode().value()).isEqualTo(502);
        assertThat(failed.getBody().path("reason").asString()).isEqualTo("MODEL_ERROR");
        assertThat(failed.getHeaders().getFirst("Content-Language")).isEqualTo("en");

        chat.answer("{\"title\": \"Harbour\"}");
        assertThat(post("/media-metadata", "{\"path\": \"" + BINARY + "\"}", Platform.TOKEN, "en").getStatusCode()
            .value()).isEqualTo(200);
    }

    @Test
    void reportsUnsupportedBinaries() {
        Beans.platform.access("notes.txt", true, "write").binary("notes.txt", "text/plain", "x".getBytes(), """
            <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
            <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "text/plain" .
            """);

        final ResponseEntity<JsonNode> response = post("/media-metadata", "{\"path\": \"notes.txt\"}", Platform.TOKEN,
            "en");

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().path("reason").asString()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void refusesPathsThatAddressAnotherResourceThanTheOneChecked() {
        // nothing is read from the platform: the mock server fails on any request not expected
        for (final String unsafe : new String[] {"communities/../secret", "communities?x=1", "a b"}) {
            final ResponseEntity<JsonNode> response = post("/topic-models", "{\"path\": \"" + unsafe + "\"}",
                Platform.TOKEN, "en");
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().path("reason").asString()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(chat.prompts()).isEmpty();
    }

    @Test
    void refusesMalformedRequests() {
        final ResponseEntity<JsonNode> response = post("/topic-models", "{not json", Platform.TOKEN, "en");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("reason").asString()).isEqualTo("INVALID_REQUEST");
    }

}
