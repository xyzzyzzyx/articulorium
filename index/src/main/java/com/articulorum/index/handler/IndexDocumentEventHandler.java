package com.articulorum.index.handler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.DocumentEventHandler;
import com.articulorum.index.solr.SolrIndexClient;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link DocumentEvent}s to the Solr collection.
 */
@Slf4j
@Component
public class IndexDocumentEventHandler implements DocumentEventHandler {

    private final SolrIndexClient solr;

    /**
     * Creates the handler.
     *
     * @param solr the Solr client
     */
    public IndexDocumentEventHandler(final SolrIndexClient solr) {
        this.solr = solr;
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
                    solr.add(toSolrDocument(event.getDocument()));
                }
                case DELETED -> {
                    log.info("UNINDEX {}", id);
                    solr.deleteById(id);
                }
                default -> log.debug("Ignoring {}", event.getAction());
            }
        } catch (RestClientException e) {
            log.warn("Unable to apply {} of {} to Solr: {}", event.getAction(), id, e.getMessage());
            log.debug("Solr failure", e);
        }
    }

    /**
     * Converts a transform document to a Solr document: the single-valued {@code id} field is
     * unwrapped; every other field stays multi-valued.
     *
     * @param document the transform document
     * @return the Solr document
     */
    static Map<String, Object> toSolrDocument(final Map<String, Collection<?>> document) {
        final Map<String, Object> solrDocument = new LinkedHashMap<>();
        document.forEach((field, values) -> {
            if (DocumentEvent.ID_FIELD.equals(field) && values != null && !values.isEmpty()) {
                solrDocument.put(field, String.valueOf(values.iterator().next()));
            } else if (values != null && !values.isEmpty()) {
                solrDocument.put(field, values);
            }
        });
        return solrDocument;
    }

}
