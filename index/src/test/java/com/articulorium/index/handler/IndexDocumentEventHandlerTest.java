package com.articulorium.index.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorium.event.DocumentEvent;
import com.articulorium.event.EventAction;
import com.articulorium.event.EventDestinations;
import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.handler.IndexDocumentEventHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * Unit tests for {@link IndexDocumentEventHandler}.
 */
class IndexDocumentEventHandlerTest {

    private static final Map<String, Collection<?>> DOCUMENT =
        Map.of("id", List.of("http://h/a"), "title", List.of("A", "B"));

    private DocumentIndex index;

    private IndexDocumentEventHandler handler;

    @BeforeEach
    void setUp() {
        index = mock(DocumentIndex.class);
        handler = new IndexDocumentEventHandler(index);
    }

    private static DocumentEvent event(final Map<String, Collection<?>> document, final EventAction action) {
        return new DocumentEvent(new Object(), "transform:1", EventDestinations.all(), document, action);
    }

    @Test
    void createdAndUpdatedDocumentsAreIndexed() {
        handler.handle(event(DOCUMENT, EventAction.CREATED));
        handler.handle(event(DOCUMENT, EventAction.UPDATED));
        verify(index, times(2)).add(Map.of("id", "http://h/a", "title", List.of("A", "B")));
    }

    @Test
    void deletedDocumentsAreRemoved() {
        handler.handle(event(Map.of("id", List.of("http://h/a")), EventAction.DELETED));
        verify(index).deleteById("http://h/a");
        verify(index, never()).add(anyMap());
    }

    @Test
    void documentsWithoutIdAreIgnored() {
        handler.handle(event(Map.of("title", List.of("A")), EventAction.CREATED));
        verify(index, never()).add(anyMap());
        verify(index, never()).deleteById(anyString());
    }

    @Test
    void indexFailuresAreSwallowed() {
        doThrow(new RestClientException("down")).when(index).add(any());
        handler.handle(event(DOCUMENT, EventAction.CREATED));
    }

    @Test
    void idIsUnwrappedAndEmptyFieldsDropped() {
        final Map<String, Object> indexDocument = IndexDocumentEventHandler.toIndexDocument(
            Map.of("id", List.of("http://h/a"), "empty", List.of(), "title", List.of("A")));
        assertThat(indexDocument).containsEntry("id", "http://h/a").containsEntry("title", List.of("A"))
            .doesNotContainKey("empty");
    }

}
