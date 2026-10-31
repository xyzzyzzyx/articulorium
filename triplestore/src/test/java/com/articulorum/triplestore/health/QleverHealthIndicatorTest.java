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
 * Unit tests for {@link QleverHealthIndicator}.
 */
class QleverHealthIndicatorTest {

    private final SparqlStoreClient store = mock(SparqlStoreClient.class);

    private final QleverHealthIndicator indicator = new QleverHealthIndicator(store, new TriplestoreProperties());

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

        assertThat(indicator.health(true).getStatus()).isEqualTo(Status.DOWN);
    }

}
