package com.articulorum.triplestore.handler;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.event.ResourceEvent;
import com.articulorum.event.handler.ResourceEventHandler;
import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Mirrors {@link ResourceEvent}s into the SPARQL store.
 *
 * <p>Every resource is stored in a named graph whose IRI is the resource IRI. Created and updated
 * resources replace their graph with the complete representation carried by the event (including
 * server-managed containment and membership triples); deleted resources drop their graph.</p>
 */
@Slf4j
@Component
public class TriplestoreResourceEventHandler implements ResourceEventHandler {

    private final SparqlStoreClient store;

    private final TriplestoreProperties properties;

    /**
     * Creates the handler.
     *
     * @param store      the SPARQL store client
     * @param properties store settings
     */
    public TriplestoreResourceEventHandler(final SparqlStoreClient store, final TriplestoreProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void handle(final ResourceEvent event) {
        final LdpResource resource = event.getResource();
        if (resource == null || event.getAction() == null) {
            return;
        }
        final String base = StringUtils.hasText(properties.getBaseUri()) ? properties.getBaseUri() : event.getBaseUri();
        final String graph = Iris.resolve(base, resource.getRelativeIri());
        try {
            switch (event.getAction()) {
                case CREATED, UPDATED -> {
                    log.info("STORE {} {} ({} triples)", event.getAction(), graph, event.getTriples().size());
                    store.replaceGraph(graph, NTriplesSerializer.serialize(event.getTriples(), base));
                }
                case DELETED -> {
                    log.info("DROP {}", graph);
                    store.dropGraph(graph);
                }
                default -> log.debug("Ignoring {}", event.getAction());
            }
        } catch (RestClientException e) {
            log.warn("Unable to apply {} of {} to the triplestore: {}", event.getAction(), graph, e.getMessage());
            log.debug("Triplestore failure", e);
        }
    }

}
