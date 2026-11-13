package com.articulorium.triplestore.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.articulorium.triplestore.backend.TripleStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link TriplestoreHealthIndicator}.
 */
class TriplestoreHealthIndicatorTest {

    private final TripleStore store = mock(TripleStore.class);

    private final TriplestoreHealthIndicator indicator = new TriplestoreHealthIndicator(store);

    @BeforeEach
    void setUp() {
        when(store.location()).thenReturn(Map.of("endpoint", "http://qlever:7001/"));
    }

    @Test
    void upWithGraphCount() {
        when(store.ping()).thenReturn(true);
        when(store.countGraphs()).thenReturn(7L);

        final Health health = indicator.health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("graphs", 7L).containsEntry("endpoint", "http://qlever:7001/");
    }

    @Test
    void downWhenUnreachable() {
        when(store.ping()).thenThrow(new ResourceAccessException("refused"));

        final Health health = indicator.health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("endpoint", "http://qlever:7001/");
    }

    @Test
    void downWhenTheGraphsCannotBeQueried() {
        when(store.ping()).thenReturn(true);
        when(store.countGraphs()).thenThrow(new ResourceAccessException("timeout"));

        assertThat(indicator.health(true).getStatus()).isEqualTo(Status.DOWN);
    }

}
