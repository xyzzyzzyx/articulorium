package com.articulorum.triplestore.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link NamespaceInitializer}.
 */
class NamespaceInitializerTest {

    @Test
    void retriesUntilTheStoreIsReady() {
        final SparqlStoreClient store = mock(SparqlStoreClient.class);
        when(store.ensureNamespace()).thenThrow(new ResourceAccessException("starting")).thenReturn(false);

        assertThat(new NamespaceInitializer(store, new TriplestoreProperties()).initialize(3, Duration.ZERO)).isTrue();
        verify(store, times(2)).ensureNamespace();
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        final SparqlStoreClient store = mock(SparqlStoreClient.class);
        when(store.ensureNamespace()).thenThrow(new ResourceAccessException("down"));

        assertThat(new NamespaceInitializer(store, new TriplestoreProperties()).initialize(2, Duration.ZERO)).isFalse();
    }

}
