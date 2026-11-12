package com.articulorium.topology.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image hints for the topology service: the built-in topologies are class path
 * resources found by pattern at start-up, and a native image holds only the resources it is told
 * about (without this the service would start with no built-in topology). On the JVM this
 * registrar has no effect.
 */
public class TopologyRuntimeHints implements RuntimeHintsRegistrar {

    /** The built-in topologies. */
    public static final String BUILT_INS = "topologies/*.json";

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        hints.resources().registerPattern(BUILT_INS);
    }

}
