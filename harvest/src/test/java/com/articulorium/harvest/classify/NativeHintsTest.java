package com.articulorium.harvest.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.articulorium.harvest.config.ClassificationConfiguration;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.ingest.PlatformAuthorization;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Tests the GraalVM native-image hints of the classification. The records exchanged with the AI
 * agent are named in no controller signature, so Spring AOT does not find them by itself: without
 * a hint a native harvester finds no serializer for the request and can read no suggestion.
 */
class NativeHintsTest {

    @Test
    void registersTheRecordsExchangedWithTheAgent() {
        final RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, AgentClient.class);

        assertThat(List.of(AgentClient.Request.class, Suggestion.class, Suggestion.Ref.class,
            Suggestion.NewStructure.class, Suggestion.Topology.class, Suggestion.Alternative.class))
            .allSatisfy(type -> assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getName())
                .accepts(hints));
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(AgentClient.Request.class, "titles"))
            .as("the request's accessors, which Jackson serializes it by").accepts(hints);
    }

    /** Spring AOT reads the hint from the bean's class, so the client must be declared as itself. */
    @Test
    void theClientIsABeanOfItsOwnClass() throws NoSuchMethodException {
        assertThat(ClassificationConfiguration.class.getMethod("agentClient",
            RestClient.Builder.class, HarvestProperties.class, PlatformAuthorization.class))
            .satisfies(method -> {
                assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
                assertThat(method.getReturnType()).isEqualTo(AgentClient.class);
            });
    }

}
