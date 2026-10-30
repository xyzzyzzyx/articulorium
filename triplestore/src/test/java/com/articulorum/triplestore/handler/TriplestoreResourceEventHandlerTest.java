package com.articulorum.triplestore.handler;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * Unit tests for {@link TriplestoreResourceEventHandler}.
 */
class TriplestoreResourceEventHandlerTest {

    private SparqlStoreClient store;

    private TriplestoreProperties properties;

    private TriplestoreResourceEventHandler handler;

    @BeforeEach
    void setUp() {
        store = mock(SparqlStoreClient.class);
        properties = new TriplestoreProperties();
        handler = new TriplestoreResourceEventHandler(store, properties);
    }

    private static ResourceEvent event(final EventAction action, final List<Triple> triples) {
        return new ResourceEvent(new Object(), "platform:1", EventDestinations.all(),
            new LdpResource("books", InteractionModel.BASIC_CONTAINER), action, "http://localhost:9000", triples);
    }

    @Test
    void createdResourcesReplaceTheirGraphWithAbsoluteIris() {
        handler.handle(event(EventAction.CREATED, List.of(Triple.iri("/books", Ldp.CONTAINS, "/books/1"))));
        verify(store).replaceGraph("http://localhost:9000/books",
            "<http://localhost:9000/books> <" + Ldp.CONTAINS + "> <http://localhost:9000/books/1> .\n");
    }

    @Test
    void configuredBaseOverridesTheEventBase() {
        properties.setBaseUri("https://data.example.org");
        handler.handle(event(EventAction.UPDATED, List.of()));
        verify(store).replaceGraph("https://data.example.org/books", "");
    }

    @Test
    void deletedResourcesDropTheirGraph() {
        handler.handle(event(EventAction.DELETED, List.of()));
        verify(store).dropGraph("http://localhost:9000/books");
        verify(store, never()).replaceGraph(anyString(), anyString());
    }

    @Test
    void storeFailuresAreSwallowed() {
        doThrow(new RestClientException("down")).when(store).dropGraph(anyString());
        handler.handle(event(EventAction.DELETED, List.of()));
    }

    @Test
    void eventsWithoutResourceAreIgnored() {
        handler.handle(new ResourceEvent());
        verify(store, never()).dropGraph(anyString());
    }

}
