package com.articulorum.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Checks the defaults the platform ships with in {@code src/main/resources/application.yml}
 * (the test class path has its own {@code application.yml}, so the file is read directly).
 */
class PlatformDefaultsTest {

    private static PropertySource<?> defaults;

    @BeforeAll
    static void load() throws IOException {
        final List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("defaults", new FileSystemResource("src/main/resources/application.yml"));
        defaults = sources.get(0);
    }

    private static String property(final String name) {
        final Object value = defaults.getProperty(name);
        return value == null ? null : value.toString();
    }

    /** PostgreSQL is the default database, configurable through POSTGRES_* variables. */
    @Test
    void defaultsToPostgres() {
        assertThat(property("spring.datasource.url"))
            .startsWith("jdbc:postgresql://")
            .contains("${POSTGRES_HOST:postgres}", "${POSTGRES_DB:articulorum}");
        assertThat(property("spring.datasource.username")).isEqualTo("${POSTGRES_USER:articulorum}");
    }

    /** Health details, probes and the actuator port are configured. */
    @Test
    void exposesActuatorEndpoints() {
        assertThat(property("management.server.port")).isEqualTo("8000");
        assertThat(property("management.endpoint.health.show-details")).isEqualTo("always");
        assertThat(property("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(property("management.endpoints.web.exposure.include")).contains("health", "info", "metrics");
    }

    /** The OpenAPI description and Swagger UI live under /.well-known, outside the LDP namespace. */
    @Test
    void publishesOpenApiUnderWellKnown() {
        assertThat(property("springdoc.api-docs.path")).isEqualTo("/.well-known/openapi");
        assertThat(property("springdoc.swagger-ui.path")).isEqualTo("/.well-known/swagger-ui.html");
    }

}
