package com.articulorum.event.listener;

import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.DocumentEventHandler;

import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DocumentEventListener implements ApplicationListener<DocumentEvent> {

    private final DocumentEventHandler handler;

    public DocumentEventListener(final DocumentEventHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onApplicationEvent(DocumentEvent event) {
        log.info("EVENT {}, ORIGIN {}, DESTINATION {}", event.getAction(), event.getOriginService(), event.getDestinationService());
        handler.handle(event);
    }

}