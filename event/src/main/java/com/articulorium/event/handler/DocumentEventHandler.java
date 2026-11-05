package com.articulorium.event.handler;

import com.articulorium.event.DocumentEvent;

/**
 * Callback for services that react to {@link DocumentEvent}s. Every bean implementing this
 * interface is invoked for each event received from the bus.
 */
@FunctionalInterface
public interface DocumentEventHandler {

    /**
     * Handles an event.
     *
     * @param event the event
     */
    void handle(DocumentEvent event);

}
