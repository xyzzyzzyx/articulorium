package com.articulorium.triplestore.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.articulorium.triplestore.backend.TripleStore;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link TriplestoreInitializer}.
 */
class TriplestoreInitializerTest {

    private final TripleStore store = mock(TripleStore.class);

    private final TriplestoreInitializer initializer = new TriplestoreInitializer(store);

    @Test
    void preparesTheStoreOnce() {
        when(store.prepare()).thenReturn(true);

        assertThat(initializer.initialize(3, Duration.ZERO)).isTrue();
        verify(store).prepare();
    }

    @Test
    void retriesWhileTheStoreIsStarting() {
        when(store.prepare()).thenThrow(new ResourceAccessException("refused"))
            .thenThrow(new ResourceAccessException("refused")).thenReturn(false);

        assertThat(initializer.initialize(5, Duration.ZERO)).isTrue();
        verify(store, times(3)).prepare();
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        when(store.prepare()).thenThrow(new ResourceAccessException("refused"));

        assertThat(initializer.initialize(2, Duration.ZERO)).isFalse();
        verify(store, times(2)).prepare();
    }

    @Test
    void stopsWhenInterrupted() {
        when(store.prepare()).thenThrow(new ResourceAccessException("refused"));
        Thread.currentThread().interrupt();
        try {
            assertThat(initializer.initialize(5, Duration.ofSeconds(30))).isFalse();
            verify(store).prepare();
        } finally {
            Thread.interrupted();
        }
    }

}
