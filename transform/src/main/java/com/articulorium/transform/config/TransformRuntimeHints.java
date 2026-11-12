package com.articulorium.transform.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image hints for the transform service: the default field mappings are a class
 * path resource read at start-up, and a native image holds only the resources it is told about.
 * On the JVM this registrar has no effect.
 */
public class TransformRuntimeHints implements RuntimeHintsRegistrar {

    /** The default field mappings ({@code articulorium.transform.field-mappings}). */
    public static final String FIELD_MAPPINGS = "transform/field-mappings.yml";

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        hints.resources().registerPattern(FIELD_MAPPINGS);
    }

}
