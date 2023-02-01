package com.articulorum.index.handler;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.DocumentEventHandler;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class IndexDocumentEventHandler implements DocumentEventHandler {

    @Autowired
    private SolrClient solrClient;

    @Override
    public void handle(DocumentEvent event) {
        Map<String, Collection<?>> document = event.getDocument();
        String id = (String) ((List<?>) document.get("id")).get(0);
        switch (event.getAction()) {
            case CREATED:
                log.info("INDEX {}", id);
                indexDocument(id, document);
                break;
            case UPDATED:
                log.info("UPDATE {}", id);
                indexDocument(id, document);
                break;
            case DELETED:
                log.info("DELETE {}", id);
                deleteDocument(id);
                break;
        }
    }

    private void indexDocument(String id, Map<String, Collection<?>> document) {
        try {
            solrClient.add("test", toSolrInputDocument(document));
            solrClient.commit("test");
        } catch (Exception e) {
            log.warn("Failed to index document {} in collection {}", id, "test");
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private void deleteDocument(String id) {
        try {
            solrClient.deleteById("test", id);
            solrClient.commit("test");
        } catch (Exception e) {
            log.warn("Failed to delete document {} from collection {}", id, "test");
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private SolrInputDocument toSolrInputDocument(Map<String, Collection<?>> document) {
        final SolrInputDocument solrInDoc = new SolrInputDocument();
        document.entrySet().forEach(entry -> {
            solrInDoc.addField(entry.getKey(), entry.getValue());
        });
        return solrInDoc;
    }

}