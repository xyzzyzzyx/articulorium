package com.articulorum.event.config;

import com.articulorum.event.ResourceEvent;
import com.articulorum.event.handler.DocumentEventHandler;
import com.articulorum.event.handler.ResourceEventHandler;
import com.articulorum.event.listener.DocumentEventListener;
import com.articulorum.event.listener.ResourceEventListener;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import tools.jackson.databind.MapperFeature;

/**
 * Registers the Articulorum event types with Spring Cloud Bus and the listeners that dispatch
 * received events to {@link ResourceEventHandler} and {@link DocumentEventHandler} beans.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(EventRuntimeHints.class)
// registers ResourceEvent and DocumentEvent (same package) as bus payload types
@RemoteApplicationEventScan(basePackageClasses = ResourceEvent.class)
public class BusConfiguration {

    /**
     * Listener dispatching resource events.
     *
     * @param handlers the resource event handlers
     * @return the listener
     */
    @Bean
    public ResourceEventListener resourceEventListener(final ObjectProvider<ResourceEventHandler> handlers) {
        return new ResourceEventListener(handlers);
    }

    /**
     * Listener dispatching document events.
     *
     * @param handlers the document event handlers
     * @return the listener
     */
    @Bean
    public DocumentEventListener documentEventListener(final ObjectProvider<DocumentEventHandler> handlers) {
        return new DocumentEventListener(handlers);
    }

    /**
     * Lets Jackson 3 populate the final fields of
     * {@link org.springframework.cloud.bus.event.RemoteApplicationEvent} ({@code originService},
     * {@code destinationService} and {@code id}).
     *
     * <p>Those fields have getters but no setters. Jackson 2 wrote them through reflection by
     * default; Jackson 3 disables {@link MapperFeature#ALLOW_FINAL_FIELDS_AS_MUTATORS}, so without
     * this customizer received events would lose their origin (which the bus uses to ignore
     * events a service sent itself) and their identifier.</p>
     *
     * @return the customizer applied to the application's {@code JsonMapper}
     */
    @Bean
    public JsonMapperBuilderCustomizer busEventFinalFieldsCustomizer() {
        return builder -> builder.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);
    }

}
