package com.articulorum.transform.handler;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import com.articulorum.domain.Container;
import com.articulorum.event.ContainerEvent;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.ContainerEventHandler;
import com.articulorum.transform.service.LdPathService;

import org.apache.marmotta.ldpath.exception.LDPathParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LdpathContainerEventHandler implements ContainerEventHandler {

    @Autowired
    private LdPathService ldPathService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private BusProperties busProperties;

    @Override
    public void handle(ContainerEvent event) {
        Container container = event.getContainer();
        final String originService = busProperties.getId();
        try {
            Map<String, Collection<?>> document = ldPathService.programQuery(container.getPath());
            log.info("EMIT {}, DOCUMENT {}, ORIGIN {}", event.getAction(), container.getId(), originService);
            publisher.publishEvent(new DocumentEvent(this, originService, document, event.getAction()));
        } catch (LDPathParseException | IOException e) {
            log.warn("Failed to process transform for container {}", container.getId());
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

}