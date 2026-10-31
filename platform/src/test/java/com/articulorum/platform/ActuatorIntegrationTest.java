package com.articulorum.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * Calls the actuator over HTTP on its own management port and checks that the platform's
 * health indicators (database, OCFL storage) report next to Spring Boot's own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "management.server.port=0",
    "management.endpoints.web.exposure.include=health,info,metrics",
    "management.endpoint.health.probes.enabled=true"
})
class ActuatorIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    private String get(final String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            return response.body();
        }
    }

    /** The aggregated health is UP and lists the custom components with their details. */
    @Test
    void healthListsTheCustomIndicators() throws Exception {
        final String health = get("/actuator/health");

        assertThat(health).contains("\"status\":\"UP\"", "\"database\"", "\"ocfl\"", "\"rootContainer\":true",
            "ocfl:filesystem:");
        assertThat(health).as("no MinIO indicator with file system storage").doesNotContain("\"minio\"");
    }

    /** Kubernetes style probes and individual components are reachable. */
    @Test
    void probesAndComponentsAreReachable() throws Exception {
        assertThat(get("/actuator/health/liveness")).contains("UP");
        assertThat(get("/actuator/health/readiness")).contains("UP");
        assertThat(get("/actuator/health/ocfl")).contains("UP");
        assertThat(get("/actuator/metrics")).contains("jvm.memory.used");
    }

}
