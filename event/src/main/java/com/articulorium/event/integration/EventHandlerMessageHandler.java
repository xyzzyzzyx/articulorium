package com.articulorium.event.integration;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.support.ErrorMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Adapts one event handler bean to a {@link MessageHandler} subscribed to an event channel.
 *
 * <p>The handler is isolated from its siblings on the same publish-subscribe channel: a failure
 * is logged and sent as an {@link ErrorMessage} to the dead letter channel instead of being
 * thrown, so the remaining handlers still run and the failure never reaches (and never makes
 * Spring Cloud Stream redeliver to) the bus consumer.</p>
 *
 * @param <E> the event type
 */
@Slf4j
public class EventHandlerMessageHandler<E> implements MessageHandler {

    private final Class<E> eventType;

    private final Consumer<? super E> handler;

    private final String handlerName;

    private final MessageChannel errorChannel;

    /**
     * Creates the adapter.
     *
     * @param eventType    the payload type of the messages it receives
     * @param handler      the callback handling one event
     * @param handlerName  the handler's name, for logs and error messages
     * @param errorChannel where failures are reported
     */
    public EventHandlerMessageHandler(final Class<E> eventType, final Consumer<? super E> handler,
            final String handlerName, final MessageChannel errorChannel) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.handlerName = Objects.requireNonNull(handlerName, "handlerName");
        this.errorChannel = Objects.requireNonNull(errorChannel, "errorChannel");
    }

    @Override
    public void handleMessage(final Message<?> message) {
        final E event = eventType.cast(message.getPayload());
        try {
            handler.accept(event);
        } catch (RuntimeException e) {
            log.error("{} handler {} failed", eventType.getSimpleName(), handlerName, e);
            reportFailure(message, e);
        }
    }

    /** @return the name of the adapted handler */
    public String getHandlerName() {
        return handlerName;
    }

    @Override
    public String toString() {
        return "EventHandlerMessageHandler[" + handlerName + "]";
    }

    private void reportFailure(final Message<?> message, final RuntimeException failure) {
        final MessageHandlingException exception = new MessageHandlingException(message,
            "Event handler " + handlerName + " failed", failure);
        try {
            errorChannel.send(new ErrorMessage(exception, message));
        } catch (RuntimeException e) {
            // a failing dead letter subscriber must not break event dispatch either
            log.warn("Unable to report the failure of {} to the error channel: {}", handlerName, e.getMessage());
            log.debug("Error channel failure", e);
        }
    }

}
