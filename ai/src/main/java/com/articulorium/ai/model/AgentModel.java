package com.articulorium.ai.model;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.Capability;
import com.articulorium.ai.config.AiProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The chat model behind every capability, reached through Spring AI's {@link ChatClient}.
 *
 * <p>Each call sends a system prompt, a user message (with images where the capability shows
 * them) and the JSON schema of the answer ({@link BeanOutputConverter}), and turns the answer into
 * a record. The agent stays usable when the model is not: a missing model, a busy agent, a failing
 * model and an unusable answer each become an {@link AgentException} with its own
 * {@link Reason}, so callers report a clear outcome and carry on.</p>
 *
 * <p>Prompts hold repository content, which is never logged: logs and the {@code ai.model.calls}
 * timer record the capability, the outcome, the duration and the size of the prompt only.</p>
 */
@Component
public class AgentModel {

    private static final Logger LOG = LoggerFactory.getLogger(AgentModel.class);

    private final ObjectProvider<ChatClient.Builder> builders;

    private final ObjectProvider<ChatModel> models;

    private final AiProperties properties;

    private final MeterRegistry meters;

    private final Semaphore permits;

    private volatile ChatClient client;

    /**
     * Creates the model access.
     *
     * @param builders   Spring AI's chat client builder (usable when a chat model is configured)
     * @param models     the chat model, for its name
     * @param properties the agent settings
     * @param meters     the meter registry
     */
    public AgentModel(final ObjectProvider<ChatClient.Builder> builders, final ObjectProvider<ChatModel> models,
            final AiProperties properties, final MeterRegistry meters) {
        this.builders = builders;
        this.models = models;
        this.properties = properties;
        this.meters = meters;
        this.permits = new Semaphore(Math.max(1, properties.getModel().getMaxConcurrency()), true);
    }

    /**
     * Tells whether a chat model is configured: exactly one, as {@code spring.ai.model.chat} selects
     * (Spring AI's chat client builder is declared even without a model, so the model is checked).
     *
     * @return {@code true} when one is
     */
    public boolean available() {
        return models.getIfUnique() != null;
    }

    /**
     * Describes the configured chat model for operators and the user interface.
     *
     * @return the provider and model name, or empty without a model
     */
    public Optional<String> describe() {
        final ChatModel model = models.getIfUnique();
        if (model == null) {
            return Optional.empty();
        }
        final String provider = model.getClass().getSimpleName().replace("ChatModel", "").toLowerCase(java.util.Locale.ROOT);
        final ChatOptions options = model.getDefaultOptions();
        final String name = options == null ? null : options.getModel();
        return Optional.of(StringUtils.hasText(name) ? provider + ":" + name : provider);
    }

    /**
     * Checks that a capability may be used now (agent and capability enabled, a model configured).
     *
     * @param capability the capability
     * @throws AgentException when it may not
     */
    public void require(final Capability capability) {
        if (!properties.isEnabled()) {
            throw new AgentException(Reason.DISABLED, "The AI agent is turned off (articulorium.ai.enabled)");
        }
        if (!capability.settings(properties).isEnabled()) {
            throw new AgentException(Reason.CAPABILITY_DISABLED,
                "The AI capability " + capability.key() + " is turned off (articulorium.ai." + capability.key() + ".enabled)");
        }
        if (!available()) {
            throw new AgentException(Reason.MODEL_UNAVAILABLE, "No chat model is configured (spring.ai.model.chat)");
        }
    }

    /**
     * Asks the model and reads its answer as a record.
     *
     * @param capability what for
     * @param prompt     the prompt
     * @param type       the record of the answer
     * @param <T>        its type
     * @return the answer
     * @throws AgentException when the capability may not be used, the agent is busy, the model fails
     *                        or its answer cannot be read
     */
    public <T> T ask(final Capability capability, final AgentPrompt prompt, final Class<T> type) {
        require(capability);
        final BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        final Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            acquire(capability);
            try {
                final String answer = call(prompt, converter.getFormat());
                if (!StringUtils.hasText(answer)) {
                    outcome = "invalid-output";
                    throw new AgentException(Reason.INVALID_OUTPUT, "The model answered nothing");
                }
                final T value;
                try {
                    value = converter.convert(answer);
                } catch (RuntimeException e) {
                    outcome = "invalid-output";
                    throw new AgentException(Reason.INVALID_OUTPUT,
                        "The model's answer does not follow the expected JSON schema: " + e.getClass().getSimpleName(), e);
                }
                if (value == null) {
                    outcome = "invalid-output";
                    throw new AgentException(Reason.INVALID_OUTPUT, "The model answered null");
                }
                outcome = "success";
                return value;
            } finally {
                permits.release();
            }
        } catch (AgentException e) {
            if ("error".equals(outcome)) {
                outcome = e.getReason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
            throw e;
        } finally {
            final long nanos = sample.stop(Timer.builder("ai.model.calls")
                .description("Calls of the chat model")
                .tag("capability", capability.key())
                .tag("outcome", outcome)
                .register(meters));
            LOG.info("AI {}: {} in {} ms (prompt {} characters, {} media)", capability.key(), outcome,
                TimeUnit.NANOSECONDS.toMillis(nanos), prompt.size(), prompt.media().size());
        }
    }

    private void acquire(final Capability capability) {
        try {
            if (!permits.tryAcquire(properties.getModel().getQueueTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AgentException(Reason.BUSY, "Too many AI requests are running; " + capability.key()
                    + " waited " + properties.getModel().getQueueTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(Reason.BUSY, "Interrupted while waiting for the model", e);
        }
    }

    private String call(final AgentPrompt prompt, final String format) {
        try {
            final List<Media> media = prompt.media();
            return client().prompt()
                .system(prompt.system())
                .user(user -> user.text(prompt.user() + "\n\n" + format).media(media.toArray(Media[]::new)))
                .call()
                .content();
        } catch (ResourceAccessException e) {
            // the cause names internal hosts: it is logged, callers get the reason only
            LOG.warn("The chat model cannot be reached: {}", abbreviate(String.valueOf(e.getMessage())));
            throw new AgentException(Reason.MODEL_UNAVAILABLE, "The chat model cannot be reached", e);
        } catch (AgentException e) {
            throw e;
        } catch (RuntimeException e) {
            // the provider's own message may describe its configuration: logged, not answered
            LOG.warn("The chat model failed: {}{}", e.getClass().getSimpleName(),
                e.getMessage() == null ? "" : ": " + abbreviate(e.getMessage()));
            throw new AgentException(Reason.MODEL_ERROR, "The chat model failed", e);
        }
    }

    private ChatClient client() {
        ChatClient current = client;
        if (current == null) {
            if (!available()) {
                throw new AgentException(Reason.MODEL_UNAVAILABLE, "No chat model is configured (spring.ai.model.chat)");
            }
            current = builders.getObject().build();
            client = current;
        }
        return current;
    }

    private static String abbreviate(final String message) {
        final String flat = message.strip().replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

}
