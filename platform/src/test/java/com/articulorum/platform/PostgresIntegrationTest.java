package com.articulorum.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.articulorum.platform.health.DatabaseHealthIndicator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Runs the platform against PostgreSQL, its default database, in a container. Skipped when
 * Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresIntegrationTest {

    /** The image docker compose uses. */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:18")
        .withEnv("POSTGRES_DB", "articulorum")
        .withEnv("POSTGRES_USER", "articulorum")
        .withEnv("POSTGRES_PASSWORD", "articulorum")
        .withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
            .withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void postgres(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/articulorum");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "articulorum");
        registry.add("spring.datasource.password", () -> "articulorum");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseHealthIndicator databaseHealth;

    /** Resources round-trip through PostgreSQL. */
    @Test
    void storesResourcesInPostgres() throws Exception {
        final String location = mvc.perform(post("/").header("Slug", "pg").contentType("text/turtle")
                .content("<> <http://purl.org/dc/terms/title> \"stored in PostgreSQL\" ."))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        mvc.perform(get(location.substring("http://localhost".length())).accept("text/turtle"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("stored in PostgreSQL")));
        mvc.perform(post(location.substring("http://localhost".length())).header("Slug", "file.txt")
                .contentType(MediaType.TEXT_PLAIN).content("bytes"))
            .andExpect(status().isCreated());
    }

    /** The health indicator reports PostgreSQL; the OCFL lock table lives in the same database. */
    @Test
    void healthAndLocksUsePostgres() {
        assertThat(databaseHealth.health(true).getStatus()).isEqualTo(Status.UP);
        assertThat((String) databaseHealth.health(true).getDetails().get("database")).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'ocfl_object_lock'", Integer.class))
            .isEqualTo(1);
    }

}
