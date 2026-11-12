package com.articulorium.transform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests that a native image holds the default field mappings.
 */
class TransformRuntimeHintsTest {

    @Test
    void includesTheDefaultFieldMappings() {
        final RuntimeHints hints = new RuntimeHints();
        new TransformRuntimeHints().registerHints(hints, getClass().getClassLoader());

        final String location = new TransformProperties().getFieldMappings();
        assertThat(location).isEqualTo("classpath:" + TransformRuntimeHints.FIELD_MAPPINGS);
        assertThat(new ClassPathResource(TransformRuntimeHints.FIELD_MAPPINGS).exists()).isTrue();
        assertThat(RuntimeHintsPredicates.resource().forResource(TransformRuntimeHints.FIELD_MAPPINGS)).accepts(hints);
    }

}
