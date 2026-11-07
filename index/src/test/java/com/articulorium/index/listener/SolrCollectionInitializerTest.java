package com.articulorium.index.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.articulorium.index.config.SolrProperties;
import com.articulorium.index.listener.SolrCollectionInitializer;
import com.articulorium.index.solr.SolrIndexClient;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
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

    @Test
    void declaresTheConfiguredFieldsOnceTheCollectionIsAvailable() {
        final SolrIndexClient solr = mock(SolrIndexClient.class);
        final SolrProperties properties = new SolrProperties();
        properties.setFields(Map.of("lastModified", "pdate"));
        when(solr.ensureCollection()).thenReturn(false);
        when(solr.ensureFields(properties.getFields())).thenReturn(List.of("lastModified"));

        assertThat(new SolrCollectionInitializer(solr, properties).initialize(1, Duration.ZERO)).isTrue();
        verify(solr).ensureFields(properties.getFields());
    }

    @Test
    void usesTheCollectionWhenSolrRefusesAFieldWithoutRetrying() {
        final SolrIndexClient solr = mock(SolrIndexClient.class);
        final SolrProperties properties = new SolrProperties();
        properties.setFields(Map.of("date", "no-such-type"));
        when(solr.ensureCollection()).thenReturn(false);
        when(solr.ensureFields(properties.getFields())).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThat(new SolrCollectionInitializer(solr, properties).initialize(3, Duration.ZERO)).isTrue();
        verify(solr, times(1)).ensureCollection();
    }

    @Test
    void declaresNoFieldsBeforeTheCollectionExists() {
        final SolrIndexClient solr = mock(SolrIndexClient.class);
        when(solr.ensureCollection()).thenThrow(new ResourceAccessException("down"));

        new SolrCollectionInitializer(solr, new SolrProperties()).initialize(1, Duration.ZERO);
        verify(solr, never()).ensureFields(org.mockito.ArgumentMatchers.anyMap());
    }

}
