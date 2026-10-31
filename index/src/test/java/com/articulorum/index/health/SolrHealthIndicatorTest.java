package com.articulorum.index.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.articulorum.index.config.SolrProperties;
import com.articulorum.index.solr.SolrIndexClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link SolrHealthIndicator}.
 */
class SolrHealthIndicatorTest {

    private final SolrIndexClient solr = mock(SolrIndexClient.class);

    private final SolrProperties properties = new SolrProperties();

    @Test
    void upWhenThePingSucceeds() {
        when(solr.ping()).thenReturn("OK");
        when(solr.countDocuments()).thenReturn(7L);

        final Health health = new SolrHealthIndicator(solr, properties).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("documents", 7L).containsEntry("collection", "articulorum");
    }

    @Test
    void downWhenThePingReportsAProblem() {
        when(solr.ping()).thenReturn("FAIL");

        assertThat(new SolrHealthIndicator(solr, properties).health(true).getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenSolrIsUnreachable() {
        when(solr.ping()).thenThrow(new ResourceAccessException("connection refused"));

        final Health health = new SolrHealthIndicator(solr, properties).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

}
