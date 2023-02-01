package com.articulorium.event.integration;

/**
 * Names of the Spring Integration channels carrying Articulorium bus events.
 *
 * <p>Events received from Spring Cloud Bus (or published locally) enter the application as
 * messages whose payload is the event itself. Each event type has its own publish-subscribe
 * channel, so components beyond the {@code *EventHandler} beans (wire taps, bridges, additional
 * flows) can subscribe to the same stream by channel name.</p>
 */
public final class EventChannels {

    /** Publish-subscribe channel of complete {@link com.articulorium.event.ResourceEvent}s. */
    public static final String RESOURCE_EVENTS = "resourceEventChannel";

    /** Publish-subscribe channel of complete {@link com.articulorium.event.DocumentEvent}s. */
    public static final String DOCUMENT_EVENTS = "documentEventChannel";

    /**
     * Dead letter channel: receives an {@link org.springframework.messaging.support.ErrorMessage}
     * for every event a handler failed to process. It has no subscribers by default (failures are
     * logged where they happen); subscribe to it to park, retry or count failed events.
     */
    public static final String HANDLER_ERRORS = "eventHandlerErrorChannel";

    private EventChannels() {
        // constants
    }

}
