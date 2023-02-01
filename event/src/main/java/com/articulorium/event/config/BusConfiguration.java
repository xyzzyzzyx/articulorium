package com.articulorium.event.config;

import com.articulorium.event.ResourceEvent;
import com.articulorium.event.integration.EventIntegrationConfiguration;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;

import tools.jackson.databind.MapperFeature;

/**
 * Registers the Articulorium event types with Spring Cloud Bus and imports the Spring
 * Integration flows ({@link EventIntegrationConfiguration}) that dispatch received events to
 * {@link com.articulorium.event.handler.ResourceEventHandler} and
 * {@link com.articulorium.event.handler.DocumentEventHandler} beans.
 */
@Configuration(proxyBeanMethods = false)
@Import(EventIntegrationConfiguration.class)
@ImportRuntimeHints(EventRuntimeHints.class)
// registers ResourceEvent and DocumentEvent (same package) as bus payload types
@RemoteApplicationEventScan(basePackageClasses = ResourceEvent.class)
public class BusConfiguration {

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
