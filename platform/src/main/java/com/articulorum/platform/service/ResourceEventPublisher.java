package com.articulorum.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes {@link ResourceEvent}s for a {@link ChangeSet}.
 *
 * <p>Event payloads (including each resource's full representation) are computed immediately,
 * inside the request transaction, but the events are only published <em>after the transaction
 * commits</em>: consumers that call back into the platform therefore always observe the
 * committed state, and a rolled back request publishes nothing.</p>
 *
 * <p>When Spring Cloud Bus is active the events carry the bus id as origin, which makes the bus
 * forward them to Kafka; without the bus (tests, stand-alone use) they are local application
 * events only.</p>
 */
@Slf4j
@Component
public class ResourceEventPublisher {

    private final ApplicationEventPublisher publisher;

    private final ObjectProvider<BusProperties> busProperties;

    private final String applicationName;

    /**
     * Creates the publisher.
     *
     * @param publisher       the application event publisher
     * @param busProperties   the bus settings, absent when the bus is disabled
     * @param applicationName the application name, used as origin without a bus
     */
    public ResourceEventPublisher(final ApplicationEventPublisher publisher,
            final ObjectProvider<BusProperties> busProperties,
            @Value("${spring.application.name:platform}") final String applicationName) {
        this.publisher = publisher;
        this.busProperties = busProperties;
        this.applicationName = applicationName;
    }

    /**
     * Prepares events for every change and publishes them after commit.
     *
     * @param changes        the changes of the current request
     * @param base           the server base of the request
     * @param representation computes the full representation of a (non-deleted) resource
     */
    public void publish(final ChangeSet changes, final String base,
            final Function<LdpResource, List<Triple>> representation) {
        final String origin = origin();
        final List<ResourceEvent> events = new ArrayList<>();
        for (final ChangeSet.Change change : changes.changes()) {
            final List<Triple> triples = change.action() == EventAction.DELETED
                ? List.of()
                : representation.apply(change.resource());
            events.add(new ResourceEvent(this, origin, EventDestinations.all(), change.resource(), change.action(),
                base, triples));
        }
        if (events.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.forEach(ResourceEventPublisher.this::emit);
                }
            });
        } else {
            events.forEach(this::emit);
        }
    }

    private void emit(final ResourceEvent event) {
        log.info("EMIT {} {} (origin {})", event.getAction(), event.getResource().getRelativeIri(), event.getOriginService());
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException e) {
            // the change is committed; a broker outage must not turn the response into an error
            log.error("Unable to publish {} event for {}", event.getAction(), event.getResource().getRelativeIri(), e);
        }
    }

    private String origin() {
        final BusProperties properties = busProperties.getIfAvailable();
        return properties != null && properties.getId() != null ? properties.getId() : applicationName;
    }

}
