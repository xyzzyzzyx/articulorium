package com.articulorum.event.config;

import com.articulorum.event.ContainerEvent;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.handler.ContainerEventHandler;
import com.articulorum.event.handler.DocumentEventHandler;
import com.articulorum.event.listener.ContainerEventListener;
import com.articulorum.event.listener.DocumentEventListener;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RemoteApplicationEventScan(basePackageClasses = { ContainerEvent.class, DocumentEvent.class })
public class BusConfiguration {

    @Bean
    @ConditionalOnBean(ContainerEventHandler.class)
    public ContainerEventListener containerEventListener(ContainerEventHandler handler) {
        return new ContainerEventListener(handler);
    }

    @Bean
    @ConditionalOnBean(DocumentEventHandler.class)
    public DocumentEventListener documentEventListener(DocumentEventHandler handler) {
        return new DocumentEventListener(handler);
    }

}