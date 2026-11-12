package com.articulorium.topology.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Tests that a native image holds every built-in topology.
 */
class TopologyRuntimeHintsTest {

    @Test
    void includesEveryBuiltInTopology() throws IOException {
        final RuntimeHints hints = new RuntimeHints();
        new TopologyRuntimeHints().registerHints(hints, getClass().getClassLoader());

        final Resource[] builtIns = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:" + TopologyRuntimeHints.BUILT_INS);

        assertThat(builtIns).isNotEmpty().allSatisfy(builtIn -> assertThat(
            RuntimeHintsPredicates.resource().forResource("topologies/" + builtIn.getFilename())).accepts(hints));
    }

}
