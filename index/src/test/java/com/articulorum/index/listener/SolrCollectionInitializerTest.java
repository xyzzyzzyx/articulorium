package com.articulorum.index.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.articulorum.index.config.SolrProperties;
import com.articulorum.index.solr.SolrIndexClient;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link SolrCollectionInitializer}.
 */
class SolrCollectionInitializerTest {

    @Test
    void retriesUntilSolrIsReady() {
        final SolrIndexClient solr = mock(SolrIndexClient.class);
        when(solr.ensureCollection()).thenThrow(new ResourceAccessException("starting")).thenReturn(true);

        final boolean ready = new SolrCollectionInitializer(solr, new SolrProperties()).initialize(3, Duration.ZERO);

        assertThat(ready).isTrue();
        verify(solr, times(2)).ensureCollection();
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        final SolrIndexClient solr = mock(SolrIndexClient.class);
        when(solr.ensureCollection()).thenThrow(new ResourceAccessException("down"));

        assertThat(new SolrCollectionInitializer(solr, new SolrProperties()).initialize(2, Duration.ZERO)).isFalse();
        verify(solr, times(2)).ensureCollection();
    }

}
