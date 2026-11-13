package com.articulorium.index.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.backend.IndexStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link IndexHealthIndicator}.
 */
class IndexHealthIndicatorTest {

    private final DocumentIndex index = mock(DocumentIndex.class);

    @BeforeEach
    void setUp() {
        when(index.location()).thenReturn(Map.of("baseUrl", "http://solr:8983/solr", "collection", "articulorium"));
    }

    @Test
    void upWhenTheIndexIsAvailable() {
        when(index.status()).thenReturn(new IndexStatus(true, "OK", 7));

        final Health health = new IndexHealthIndicator(index).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("documents", 7L).containsEntry("collection", "articulorium")
            .containsEntry("status", "OK");
    }

    @Test
    void downWhenTheBackendReportsAProblem() {
        when(index.status()).thenReturn(new IndexStatus(false, "FAIL", -1));

        final Health health = new IndexHealthIndicator(index).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "FAIL").doesNotContainKey("documents");
    }

    @Test
    void downWhenTheBackendIsUnreachable() {
        when(index.status()).thenThrow(new ResourceAccessException("connection refused"));

        final Health health = new IndexHealthIndicator(index).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error").containsEntry("collection", "articulorium");
    }

}
