package com.articulorum.triplestore.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link BlazegraphHealthIndicator}.
 */
class BlazegraphHealthIndicatorTest {

    private final SparqlStoreClient store = mock(SparqlStoreClient.class);

    private final TriplestoreProperties properties = new TriplestoreProperties();

    @Test
    void upWhenTheEndpointAnswers() {
        when(store.ask("ASK {}")).thenReturn(false);
        when(store.countGraphs()).thenReturn(42L);

        final Health health = new BlazegraphHealthIndicator(store, properties).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("graphs", 42L)
            .containsEntry("namespace", properties.getNamespace())
            .containsEntry("endpoint", properties.sparqlEndpoint());
    }

    @Test
    void downWhenTheEndpointIsUnreachable() {
        when(store.ask("ASK {}")).thenThrow(new ResourceAccessException("connection refused"));

        final Health health = new BlazegraphHealthIndicator(store, properties).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

}
