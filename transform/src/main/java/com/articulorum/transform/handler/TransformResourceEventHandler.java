package com.articulorum.transform.handler;

import java.util.Collection;
import java.util.Map;

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import com.articulorum.event.handler.ResourceEventHandler;
import com.articulorum.transform.service.TransformService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns {@link ResourceEvent}s into {@link DocumentEvent}s with the active transformers.
 *
 * <p>For deleted resources a document holding only the identifier is published so the index can
 * remove it. A failing transformation is logged and skipped; it never stops the bus listener.</p>
 */
@Slf4j
@Component
public class TransformResourceEventHandler implements ResourceEventHandler {

    private final TransformService transformService;

    private final ApplicationEventPublisher publisher;

    private final ObjectProvider<BusProperties> busProperties;

    private final String applicationName;

    /**
     * Creates the handler.
     *
     * @param transformService the transformation service
     * @param publisher        the event publisher
     * @param busProperties    bus settings (absent when the bus is disabled)
     * @param applicationName  the application name, used as origin without a bus
     */
    public TransformResourceEventHandler(final TransformService transformService,
            final ApplicationEventPublisher publisher, final ObjectProvider<BusProperties> busProperties,
            @Value("${spring.application.name:transform}") final String applicationName) {
        this.transformService = transformService;
        this.publisher = publisher;
        this.busProperties = busProperties;
        this.applicationName = applicationName;
    }

    @Override
    public void handle(final ResourceEvent event) {
        if (event.getResource() == null || event.getAction() == null) {
            return;
        }
        try {
            final Map<String, Collection<?>> document = transformService.transform(event);
            final String origin = origin();
            log.info("EMIT {} document {} (origin {})", event.getAction(), document.get(DocumentEvent.ID_FIELD), origin);
            publisher.publishEvent(new DocumentEvent(this, origin, EventDestinations.all(), document, event.getAction()));
        } catch (RuntimeException e) {
            log.warn("Unable to transform {} {}: {}", event.getAction(), event.getResource().getRelativeIri(),
                e.getMessage());
            log.debug("Transformation failure", e);
        }
    }

    private String origin() {
        final BusProperties properties = busProperties.getIfAvailable();
        return properties != null && properties.getId() != null ? properties.getId() : applicationName;
    }

}
