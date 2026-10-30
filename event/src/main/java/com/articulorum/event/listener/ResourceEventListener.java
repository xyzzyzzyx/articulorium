package com.articulorum.event.listener;

import com.articulorum.event.ResourceEvent;
import com.articulorum.event.handler.ResourceEventHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches {@link ResourceEvent}s to every {@link ResourceEventHandler} in the context.
 *
 * <p>Handlers are looked up lazily, so services without handlers (such as the platform itself,
 * which only publishes) pay nothing. A failing handler is logged and does not prevent the
 * remaining handlers from running.</p>
 */
@Slf4j
public class ResourceEventListener implements ApplicationListener<ResourceEvent> {

    private final ObjectProvider<ResourceEventHandler> handlers;

    /**
     * Creates the listener.
     *
     * @param handlers provider of the handlers to notify
     */
    public ResourceEventListener(final ObjectProvider<ResourceEventHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void onApplicationEvent(final ResourceEvent event) {
        log.debug("Received {} for {} from {}", event.getAction(),
            event.getResource() == null ? null : event.getResource().getPath(), event.getOriginService());
        handlers.orderedStream().forEach(handler -> {
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                log.error("Resource event handler {} failed", handler.getClass().getName(), e);
            }
        });
    }

}
