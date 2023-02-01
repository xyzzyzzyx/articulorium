package com.articulorum.event.listener;

import com.articulorum.event.ContainerEvent;
import com.articulorum.event.handler.ContainerEventHandler;

import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContainerEventListener implements ApplicationListener<ContainerEvent> {

    private final ContainerEventHandler handler;

    public ContainerEventListener(final ContainerEventHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onApplicationEvent(ContainerEvent event) {
        log.info("EVENT {}, ORIGIN {}, DESTINATION {}", event.getAction(), event.getOriginService(), event.getDestinationService());
        handler.handle(event);
    }

}