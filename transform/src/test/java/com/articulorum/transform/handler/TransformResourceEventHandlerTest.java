package com.articulorum.transform.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import com.articulorum.transform.service.TransformService;
import com.articulorum.transform.spi.TransformationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link TransformResourceEventHandler}.
 */
class TransformResourceEventHandlerTest {

    private TransformService service;

    private ApplicationEventPublisher publisher;

    private StaticListableBeanFactory beans;

    private TransformResourceEventHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(TransformService.class);
        publisher = mock(ApplicationEventPublisher.class);
        beans = new StaticListableBeanFactory();
        handler = new TransformResourceEventHandler(service, publisher, beans.getBeanProvider(BusProperties.class),
            "transform");
    }

    private static ResourceEvent event(final EventAction action) {
        return new ResourceEvent(TransformResourceEventHandlerTest.class, "platform", EventDestinations.all(),
            new LdpResource("books/1", InteractionModel.RDF_SOURCE), action, "http://localhost:9000", List.of());
    }

    /** The transformed document is published with the same action; origin falls back to the app name. */
    @Test
    void publishesDocumentEvents() {
        when(service.transform(any())).thenReturn(Map.of(DocumentEvent.ID_FIELD, List.of("http://localhost:9000/books/1")));

        handler.handle(event(EventAction.UPDATED));

        final ArgumentCaptor<DocumentEvent> published = ArgumentCaptor.forClass(DocumentEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().getAction()).isEqualTo(EventAction.UPDATED);
        assertThat(published.getValue().getOriginService()).isEqualTo("transform");
        assertThat(published.getValue().documentId()).isEqualTo("http://localhost:9000/books/1");
    }

    /** With a bus, its id is the origin. */
    @Test
    void usesTheBusIdAsOrigin() {
        final BusProperties bus = new BusProperties();
        bus.setId("transform:abc");
        beans.addBean("bus", bus);
        when(service.transform(any())).thenReturn(Map.of(DocumentEvent.ID_FIELD, List.of("x")));

        handler.handle(event(EventAction.CREATED));

        final ArgumentCaptor<DocumentEvent> published = ArgumentCaptor.forClass(DocumentEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().getOriginService()).isEqualTo("transform:abc");
    }

    /** Failures are logged, never thrown at the bus listener. */
    @Test
    void swallowsTransformationFailures() {
        when(service.transform(any())).thenThrow(new TransformationException("boom"));

        handler.handle(event(EventAction.CREATED));

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    /** Incomplete events are ignored. */
    @Test
    void ignoresIncompleteEvents() {
        handler.handle(new ResourceEvent());
        handler.handle(event(null));

        verify(service, never()).transform(any());
    }

}
