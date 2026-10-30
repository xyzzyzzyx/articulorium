package com.articulorum.index.handler;

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

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.index.solr.SolrIndexClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * Unit tests for {@link IndexDocumentEventHandler}.
 */
class IndexDocumentEventHandlerTest {

    private static final Map<String, Collection<?>> DOCUMENT =
        Map.of("id", List.of("http://h/a"), "title", List.of("A", "B"));

    private SolrIndexClient solr;

    private IndexDocumentEventHandler handler;

    @BeforeEach
    void setUp() {
        solr = mock(SolrIndexClient.class);
        handler = new IndexDocumentEventHandler(solr);
    }

    private static DocumentEvent event(final Map<String, Collection<?>> document, final EventAction action) {
        return new DocumentEvent(new Object(), "transform:1", EventDestinations.all(), document, action);
    }

    @Test
    void createdAndUpdatedDocumentsAreIndexed() {
        handler.handle(event(DOCUMENT, EventAction.CREATED));
        handler.handle(event(DOCUMENT, EventAction.UPDATED));
        verify(solr, times(2)).add(Map.of("id", "http://h/a", "title", List.of("A", "B")));
    }

    @Test
    void deletedDocumentsAreRemoved() {
        handler.handle(event(Map.of("id", List.of("http://h/a")), EventAction.DELETED));
        verify(solr).deleteById("http://h/a");
        verify(solr, never()).add(anyMap());
    }

    @Test
    void documentsWithoutIdAreIgnored() {
        handler.handle(event(Map.of("title", List.of("A")), EventAction.CREATED));
        verify(solr, never()).add(anyMap());
        verify(solr, never()).deleteById(anyString());
    }

    @Test
    void solrFailuresAreSwallowed() {
        doThrow(new RestClientException("down")).when(solr).add(any());
        handler.handle(event(DOCUMENT, EventAction.CREATED));
    }

    @Test
    void idIsUnwrappedAndEmptyFieldsDropped() {
        final Map<String, Object> solrDocument = IndexDocumentEventHandler.toSolrDocument(
            Map.of("id", List.of("http://h/a"), "empty", List.of(), "title", List.of("A")));
        assertThat(solrDocument).containsEntry("id", "http://h/a").containsEntry("title", List.of("A"))
            .doesNotContainKey("empty");
    }

}
