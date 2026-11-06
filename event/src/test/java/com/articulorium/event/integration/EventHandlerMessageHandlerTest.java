package com.articulorium.event.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import com.articulorium.domain.InteractionModel;
import com.articulorium.domain.LdpResource;
import com.articulorium.event.EventAction;
import com.articulorium.event.EventDestinations;
import com.articulorium.event.ResourceEvent;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Unit tests for {@link EventHandlerMessageHandler}.
 */
class EventHandlerMessageHandlerTest {

    private final List<Message<?>> errors = new ArrayList<>();

    private Message<ResourceEvent> message() {
        return MessageBuilder.withPayload(new ResourceEvent(this, "platform:1", EventDestinations.all(),
            new LdpResource("books", InteractionModel.BASIC_CONTAINER), EventAction.CREATED,
            "http://localhost:9000", List.of())).build();
    }

    @Test
    void passesThePayloadToTheHandler() {
        final List<ResourceEvent> handled = new ArrayList<>();
        final Message<ResourceEvent> message = message();

        new EventHandlerMessageHandler<>(ResourceEvent.class, handled::add, "recorder", (m, t) -> errors.add(m))
            .handleMessage(message);

        assertThat(handled).containsExactly(message.getPayload());
        assertThat(errors).isEmpty();
    }

    @Test
    void reportsFailuresToTheErrorChannelInsteadOfThrowing() {
        final IllegalStateException failure = new IllegalStateException("boom");
        final Message<ResourceEvent> message = message();
        final EventHandlerMessageHandler<ResourceEvent> handler = new EventHandlerMessageHandler<>(ResourceEvent.class,
            event -> {
                throw failure;
            }, "failing", (m, t) -> errors.add(m));

        assertThatCode(() -> handler.handleMessage(message)).doesNotThrowAnyException();

        assertThat(errors).singleElement().isInstanceOfSatisfying(ErrorMessage.class, error -> {
            assertThat(error.getOriginalMessage()).isSameAs(message);
            assertThat(error.getPayload()).isInstanceOfSatisfying(MessageHandlingException.class, exception -> {
                assertThat(exception.getFailedMessage()).isSameAs(message);
                assertThat(exception.getCause()).isSameAs(failure);
                assertThat(exception.getMessage()).contains("failing");
            });
        });
    }

    @Test
    void survivesAFailingErrorChannel() {
        final EventHandlerMessageHandler<ResourceEvent> handler = new EventHandlerMessageHandler<>(ResourceEvent.class,
            event -> {
                throw new IllegalStateException("boom");
            }, "failing", (m, t) -> {
                throw new IllegalStateException("dead letter subscriber down");
            });

        assertThatCode(() -> handler.handleMessage(message())).doesNotThrowAnyException();
    }

    @Test
    void exposesTheHandlerName() {
        final EventHandlerMessageHandler<ResourceEvent> handler = new EventHandlerMessageHandler<>(ResourceEvent.class,
            event -> { }, "recorder", (m, t) -> true);

        assertThat(handler.getHandlerName()).isEqualTo("recorder");
        assertThat(handler).hasToString("EventHandlerMessageHandler[recorder]");
    }

}
