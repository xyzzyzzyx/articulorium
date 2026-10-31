package com.articulorum.event.handler;

import com.articulorum.event.ResourceEvent;

/**
 * Callback for services that react to {@link ResourceEvent}s. Every bean implementing this
 * interface is invoked for each event received from the bus.
 */
@FunctionalInterface
public interface ResourceEventHandler {

    /**
     * Handles an event.
     *
     * @param event the event
     */
    void handle(ResourceEvent event);

}
