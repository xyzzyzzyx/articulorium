package com.articulorum.topology.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;

/**
 * JSON settings of the topology API.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    /**
     * Lets clients omit boolean and number fields of topology definitions (for example
     * {@code required} of a metadata field or {@code min} of a child rule), which then take
     * their Java defaults ({@code false}, {@code 0}).
     *
     * @return the customizer
     */
    @Bean
    public JsonMapperBuilderCustomizer lenientPrimitivesCustomizer() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

}
