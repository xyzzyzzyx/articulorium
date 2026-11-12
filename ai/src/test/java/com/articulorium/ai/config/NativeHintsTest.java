package com.articulorium.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.articulorium.ai.classification.Classifier;
import com.articulorium.ai.media.MediaDescriber;
import com.articulorium.ai.profiles.ProfileEnhancer;
import com.articulorium.ai.topics.TopicModeler;
import com.articulorium.ai.topology.TopologyAssistant;
import com.articulorium.ai.topology.TopologyClient;
import com.articulorium.ai.topology.TopologyDraft;
import com.articulorium.ai.topology.TopologyValidation;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Tests the GraalVM native-image hints of the agent. The records the model's answers are read
 * into, and those exchanged with the topology service, are never named in a controller signature,
 * so Spring AOT does not find them by itself: without a hint a native agent could neither describe
 * their JSON schema to the model nor read an answer.
 */
class NativeHintsTest {

    @Test
    void registersTheRecordsReadFromTheModelAndTheTopologyService() {
        final RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, Classifier.class, MediaDescriber.class,
            ProfileEnhancer.class, TopicModeler.class, TopologyAssistant.class, TopologyClient.class);

        assertThat(List.of(Classifier.Answer.class, Classifier.AnswerStructure.class, Classifier.AnswerAlternative.class,
            MediaDescriber.Answer.class, ProfileEnhancer.Answer.class, TopicModeler.Answer.class,
            TopicModeler.Answer.Topic.class, TopologyAssistant.Advice.class, TopologyDraft.class, TopologyDraft.Node.class,
            TopologyValidation.class))
            .allSatisfy(type -> assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getName())
                .accepts(hints));
    }

}
