package com.articulorium.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.Capability;
import com.articulorium.ai.support.Agents;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * Tests the model access: structured answers, and a clear reason for every way a call can fail.
 */
class AgentModelTest {

    record Answer(String title, List<String> subjects) {
    }

    @Test
    void readsTheAnswerAsARecordAndSendsTheSchemaAndTheMedia() {
        final Agents agents = new Agents();
        agents.chat.answer("```json\n{\"title\": \"Harbour\", \"subjects\": [\"ships\"]}\n```");
        final Media image = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1, 2, 3}).build();

        final Answer answer = agents.model.ask(Capability.MEDIA,
            new AgentPrompt("system rules", "the material", List.of(image)), Answer.class);

        assertThat(answer).isEqualTo(new Answer("Harbour", List.of("ships")));
        final Prompt prompt = agents.chat.prompts().get(0);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("system rules");
        final UserMessage user = prompt.getUserMessage();
        assertThat(user.getText()).startsWith("the material").contains("\"$schema\"").contains("\"subjects\"");
        assertThat(user.getMedia()).hasSize(1);
        assertThat(agents.meters.get("ai.model.calls").tag("capability", "media").tag("outcome", "success").timer()
            .count()).isEqualTo(1);
    }

    @Test
    void refusesWhenTheAgentIsTurnedOff() {
        final Agents agents = new Agents();
        agents.properties.setEnabled(false);

        assertThatThrownBy(() -> agents.model.ask(Capability.TOPICS, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesWhenTheCapabilityIsTurnedOff() {
        final Agents agents = new Agents();
        agents.properties.getTopics().setEnabled(false);

        assertThatThrownBy(() -> agents.model.require(Capability.TOPICS))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.CAPABILITY_DISABLED));
        agents.model.require(Capability.MEDIA);
    }

    @Test
    void refusesWithoutAModel() {
        final Agents agents = new Agents(false);

        assertThat(agents.model.available()).isFalse();
        assertThat(agents.model.describe()).isEmpty();
        assertThatThrownBy(() -> agents.model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.MODEL_UNAVAILABLE));
    }

    @Test
    void reportsAFailingModelWithoutPassingOnWhatTheProviderSaid() {
        final Agents agents = new Agents();
        agents.chat.answer(new IllegalStateException("rate limited for organization org-internal-42"));

        assertThatThrownBy(() -> agents.model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class, e -> {
                assertThat(e.getReason()).isEqualTo(Reason.MODEL_ERROR);
                assertThat(e.getMessage()).isEqualTo("The chat model failed");
            });
        assertThat(agents.meters.get("ai.model.calls").tag("outcome", "model-error").timer().count()).isEqualTo(1);
    }

    @Test
    void reportsAnUnreachableModelAsUnavailable() {
        final Agents agents = new Agents();
        agents.chat.answer(new ResourceAccessException("I/O error on POST request for http://ollama:11434/api/chat"));

        assertThatThrownBy(() -> agents.model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class, e -> {
                assertThat(e.getReason()).isEqualTo(Reason.MODEL_UNAVAILABLE);
                // internal hosts stay in the log
                assertThat(e.getMessage()).doesNotContain("ollama");
            });
    }

    @Test
    void reportsAnswersThatAreNoJson() {
        final Agents agents = new Agents();
        agents.chat.answer("I am sorry, I cannot help with that.", "  ");

        assertThatThrownBy(() -> agents.model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_OUTPUT));
        assertThatThrownBy(() -> agents.model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_OUTPUT));
        assertThat(agents.meters.get("ai.model.calls").tag("outcome", "invalid-output").timer().count()).isEqualTo(2);
    }

    @Test
    void refusesCallsBeyondTheConcurrencyLimitAsBusy() throws Exception {
        final Agents agents = new Agents(false);
        agents.properties.getModel().setMaxConcurrency(1);
        agents.properties.getModel().setQueueTimeout(Duration.ofMillis(50));
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final org.springframework.ai.chat.model.ChatModel slow = prompt -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage("{\"title\": \"t\"}"))));
        };
        final org.springframework.beans.factory.support.DefaultListableBeanFactory beans =
            new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("model", slow);
        beans.registerSingleton("builder", org.springframework.ai.chat.client.ChatClient.builder(slow));
        final AgentModel model = new AgentModel(beans.getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class),
            beans.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class), agents.properties, agents.meters);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<Answer> first = executor.submit(() -> model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"),
                Answer.class));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.BUSY));

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).title()).isEqualTo("t");
            // the slot is free again
            assertThat(model.ask(Capability.MEDIA, AgentPrompt.of("s", "u"), Answer.class).title()).isEqualTo("t");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void describesTheModel() {
        final Agents agents = new Agents();

        assertThat(agents.model.describe()).hasValueSatisfying(name -> assertThat(name).startsWith("scripted"));
    }

}
