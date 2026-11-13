package com.articulorium.index.handler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.articulorium.event.DocumentEvent;
import com.articulorium.event.handler.DocumentEventHandler;
import com.articulorium.index.backend.DocumentIndex;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link DocumentEvent}s to the search index (Solr or Elasticsearch).
 */
@Slf4j
@Component
public class IndexDocumentEventHandler implements DocumentEventHandler {

    private final DocumentIndex index;

    /**
     * Creates the handler.
     *
     * @param index the search index
     */
    public IndexDocumentEventHandler(final DocumentIndex index) {
        this.index = index;
    }

    @Override
    public void handle(final DocumentEvent event) {
        final String id = event.documentId();
        if (id == null || event.getAction() == null) {
            log.warn("Ignoring document event without id or action from {}", event.getOriginService());
            return;
        }
        try {
            switch (event.getAction()) {
                case CREATED, UPDATED -> {
                    log.info("INDEX {} ({})", id, event.getAction());
                    index.add(toIndexDocument(event.getDocument()));
                }
                case DELETED -> {
                    log.info("UNINDEX {}", id);
                    index.deleteById(id);
                }
                default -> log.debug("Ignoring {}", event.getAction());
            }
        } catch (RestClientException e) {
            log.warn("Unable to apply {} of {} to {}: {}", event.getAction(), id, index.target(), e.getMessage());
            log.debug("Search index failure", e);
        }
    }

    /**
     * Converts a transform document to an index document: the single-valued {@code id} field is
     * unwrapped; every other field stays multi-valued.
     *
     * @param document the transform document
     * @return the index document
     */
    static Map<String, Object> toIndexDocument(final Map<String, Collection<?>> document) {
        final Map<String, Object> indexDocument = new LinkedHashMap<>();
        document.forEach((field, values) -> {
            if (DocumentEvent.ID_FIELD.equals(field) && values != null && !values.isEmpty()) {
                indexDocument.put(field, String.valueOf(values.iterator().next()));
            } else if (values != null && !values.isEmpty()) {
                indexDocument.put(field, values);
            }
        });
        return indexDocument;
    }

}
