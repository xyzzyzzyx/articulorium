package com.articulorium.index.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.articulorium.index.backend.DocumentIndex;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Unit tests for {@link IndexInitializer}.
 */
class IndexInitializerTest {

    private final DocumentIndex index = mock(DocumentIndex.class);

    @Test
    void retriesUntilTheBackendIsReady() {
        when(index.ensureIndex()).thenThrow(new ResourceAccessException("starting")).thenReturn(true);

        final boolean ready = new IndexInitializer(index).initialize(3, Duration.ZERO);

        assertThat(ready).isTrue();
        verify(index, times(2)).ensureIndex();
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        when(index.ensureIndex()).thenThrow(new ResourceAccessException("down"));

        assertThat(new IndexInitializer(index).initialize(2, Duration.ZERO)).isFalse();
        verify(index, times(2)).ensureIndex();
    }

    @Test
    void declaresTheConfiguredFieldsOnceTheIndexIsAvailable() {
        when(index.ensureIndex()).thenReturn(false);
        when(index.ensureFields()).thenReturn(List.of("lastModified"));

        assertThat(new IndexInitializer(index).initialize(1, Duration.ZERO)).isTrue();
        verify(index).ensureFields();
    }

    @Test
    void usesTheIndexWhenTheBackendRefusesAFieldWithoutRetrying() {
        when(index.ensureIndex()).thenReturn(false);
        when(index.ensureFields()).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThat(new IndexInitializer(index).initialize(3, Duration.ZERO)).isTrue();
        verify(index, times(1)).ensureIndex();
    }

    @Test
    void declaresNoFieldsBeforeTheIndexExists() {
        when(index.ensureIndex()).thenThrow(new ResourceAccessException("down"));

        new IndexInitializer(index).initialize(1, Duration.ZERO);
        verify(index, never()).ensureFields();
    }

}
