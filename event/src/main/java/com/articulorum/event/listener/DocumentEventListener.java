package com.articulorum.event.listener;

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.DocumentEventHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches {@link DocumentEvent}s to every {@link DocumentEventHandler} in the context.
 * A failing handler is logged and does not prevent the remaining handlers from running.
 */
@Slf4j
public class DocumentEventListener implements ApplicationListener<DocumentEvent> {

    private final ObjectProvider<DocumentEventHandler> handlers;

    /**
     * Creates the listener.
     *
     * @param handlers provider of the handlers to notify
     */
    public DocumentEventListener(final ObjectProvider<DocumentEventHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void onApplicationEvent(final DocumentEvent event) {
        log.debug("Received {} for {} from {}", event.getAction(), event.documentId(), event.getOriginService());
        handlers.orderedStream().forEach(handler -> {
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                log.error("Document event handler {} failed", handler.getClass().getName(), e);
            }
        });
    }

}
