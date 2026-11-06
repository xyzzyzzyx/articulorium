package com.articulorium.event.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.articulorium.domain.InteractionModel;
import com.articulorium.domain.LdpResource;
import com.articulorium.event.DocumentEvent;
import com.articulorium.event.EventAction;
import com.articulorium.event.EventDestinations;
import com.articulorium.event.ResourceEvent;
import com.articulorium.event.handler.DocumentEventHandler;
import com.articulorium.event.handler.ResourceEventHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.support.ErrorMessage;

/**
 * Tests of the event message flows of {@link EventIntegrationConfiguration} in an application
 * context: events published as application events (which is how Spring Cloud Bus delivers
 * remote events) must reach the handler beans through the channels.
 */
class EventIntegrationConfigurationTest {

    /** Calls recorded by the handlers of one context. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(EventIntegrationConfiguration.class);

    private static ResourceEvent resourceEvent(final EventAction action) {
        return new ResourceEvent(EventIntegrationConfigurationTest.class, "platform:1", EventDestinations.all(),
            new LdpResource("books", InteractionModel.BASIC_CONTAINER), action, "http://localhost:9000", List.of());
    }

    private static DocumentEvent documentEvent(final String id) {
        final Map<String, Collection<?>> document = id == null ? Map.of() : Map.of(DocumentEvent.ID_FIELD, List.of(id));
        return new DocumentEvent(EventIntegrationConfigurationTest.class, "transform:1", EventDestinations.all(),
            document, EventAction.UPDATED);
    }

    /** A resource event handler with an explicit order that records its calls. */
    private static class OrderedHandler implements ResourceEventHandler, Ordered {

        private final String name;

        private final int order;

        private final List<String> calls;

        OrderedHandler(final String name, final int order, final List<String> calls) {
            this.name = name;
            this.order = order;
            this.calls = calls;
        }

        @Override
        public void handle(final ResourceEvent event) {
            calls.add(name + ":" + event.getAction());
        }

        @Override
        public int getOrder() {
            return order;
        }

    }

    @Test
    void dispatchesResourceEventsToEveryHandlerInOrder() {
        runner
            .withBean("second", ResourceEventHandler.class, () -> new OrderedHandler("second", 2, calls))
            .withBean("first", ResourceEventHandler.class, () -> new OrderedHandler("first", 1, calls))
            .run(context -> {
                context.publishEvent(resourceEvent(EventAction.CREATED));

                assertThat(calls).containsExactly("first:CREATED", "second:CREATED");
            });
    }

    @Test
    void failingHandlerDoesNotStopOthersAndReachesTheDeadLetterChannel() {
        final List<Message<?>> deadLetters = new CopyOnWriteArrayList<>();
        runner
            .withBean("failing", ResourceEventHandler.class, () -> new OrderedHandler("failing", 1, calls) {
                @Override
                public void handle(final ResourceEvent event) {
                    throw new IllegalStateException("boom");
                }
            })
            .withBean("working", ResourceEventHandler.class, () -> new OrderedHandler("working", 2, calls))
            .run(context -> {
                errorChannel(context).subscribe(deadLetters::add);

                context.publishEvent(resourceEvent(EventAction.UPDATED));

                assertThat(calls).containsExactly("working:UPDATED");
                assertThat(deadLetters).singleElement().isInstanceOfSatisfying(ErrorMessage.class,
                    error -> assertThat(error.getPayload()).isInstanceOf(MessageHandlingException.class)
                        .hasRootCauseInstanceOf(IllegalStateException.class));
            });
    }

    @Test
    void dropsIncompleteResourceEvents() {
        runner
            .withBean("handler", ResourceEventHandler.class, () -> event -> calls.add("handled"))
            .run(context -> {
                context.publishEvent(resourceEvent(null));
                context.publishEvent(new ResourceEvent(this, "platform:1", EventDestinations.all(), null,
                    EventAction.CREATED, "http://localhost:9000", List.of()));

                assertThat(calls).isEmpty();
            });
    }

    @Test
    void routesDocumentEventsToDocumentHandlersOnly() {
        runner
            .withBean("resources", ResourceEventHandler.class, () -> event -> calls.add("resource"))
            .withBean("documents", DocumentEventHandler.class, () -> event -> calls.add("document:" + event.documentId()))
            .run(context -> {
                context.publishEvent(documentEvent("http://localhost:9000/books"));

                assertThat(calls).containsExactly("document:http://localhost:9000/books");
            });
    }

    @Test
    void dropsDocumentEventsWithoutIdentifier() {
        runner
            .withBean("documents", DocumentEventHandler.class, () -> event -> calls.add("document"))
            .run(context -> {
                context.publishEvent(documentEvent(null));

                assertThat(calls).isEmpty();
            });
    }

    @Test
    void toleratesAbsenceOfHandlers() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.publishEvent(resourceEvent(EventAction.DELETED));
            context.publishEvent(documentEvent("http://localhost:9000/books"));
            assertThat(context.getBean(EventChannels.RESOURCE_EVENTS, PublishSubscribeChannel.class).getSubscriberCount())
                .isZero();
        });
    }

    @Test
    void otherComponentsCanSubscribeToTheEventChannels() {
        final List<Object> tapped = new CopyOnWriteArrayList<>();
        runner.run(context -> {
            context.getBean(EventChannels.RESOURCE_EVENTS, PublishSubscribeChannel.class)
                .subscribe(message -> tapped.add(message.getPayload()));
            final ResourceEvent event = resourceEvent(EventAction.CREATED);

            context.publishEvent(event);

            assertThat(tapped).containsExactly(event);
        });
    }

    private static PublishSubscribeChannel errorChannel(final ApplicationContext context) {
        return context.getBean(EventChannels.HANDLER_ERRORS, PublishSubscribeChannel.class);
    }

}
