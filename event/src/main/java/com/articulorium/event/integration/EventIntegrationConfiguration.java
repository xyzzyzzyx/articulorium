package com.articulorium.event.integration;

import com.articulorium.event.DocumentEvent;
import com.articulorium.event.ResourceEvent;
import com.articulorium.event.handler.DocumentEventHandler;
import com.articulorium.event.handler.ResourceEventHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.event.inbound.ApplicationEventListeningMessageProducer;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring Integration message flows dispatching Articulorium bus events.
 *
 * <pre>
 * Spring Cloud Bus / local publishEvent
 *   └─ ApplicationEventListeningMessageProducer   (channel adapter)
 *        └─ filter: complete events only           (message filter)
 *             └─ resourceEventChannel / documentEventChannel   (publish-subscribe channel)
 *                  ├─ ResourceEventHandler / DocumentEventHandler beans, in order
 *                  └─ failures ─&gt; eventHandlerErrorChannel      (dead letter channel)
 * </pre>
 *
 * <p>Dispatch is synchronous, on the thread that delivered the event, exactly like the plain
 * application listeners this replaces. Every endpoint and channel is visible in the actuator
 * {@code integrationgraph} endpoint and reports Micrometer metrics
 * ({@code spring.integration.*}).</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableIntegration
public class EventIntegrationConfiguration {

    /**
     * @return the channel of complete resource events
     */
    @Bean(name = EventChannels.RESOURCE_EVENTS)
    public PublishSubscribeChannel resourceEventChannel() {
        return new PublishSubscribeChannel();
    }

    /**
     * @return the channel of complete document events
     */
    @Bean(name = EventChannels.DOCUMENT_EVENTS)
    public PublishSubscribeChannel documentEventChannel() {
        return new PublishSubscribeChannel();
    }

    /**
     * @return the dead letter channel of failed handler invocations
     */
    @Bean(name = EventChannels.HANDLER_ERRORS)
    public PublishSubscribeChannel eventHandlerErrorChannel() {
        return new PublishSubscribeChannel();
    }

    /**
     * @return the inbound channel adapter turning resource application events into messages
     */
    @Bean
    public ApplicationEventListeningMessageProducer resourceEventProducer() {
        final ApplicationEventListeningMessageProducer producer = new ApplicationEventListeningMessageProducer();
        producer.setEventTypes(ResourceEvent.class);
        return producer;
    }

    /**
     * @return the inbound channel adapter turning document application events into messages
     */
    @Bean
    public ApplicationEventListeningMessageProducer documentEventProducer() {
        final ApplicationEventListeningMessageProducer producer = new ApplicationEventListeningMessageProducer();
        producer.setEventTypes(DocumentEvent.class);
        return producer;
    }

    /**
     * Resource events without a resource or an action cannot be processed; they are dropped
     * before they reach the handlers.
     *
     * @param producer the resource event adapter
     * @return the flow from the adapter to {@value EventChannels#RESOURCE_EVENTS}
     */
    @Bean
    public IntegrationFlow resourceEventFlow(
            @Qualifier("resourceEventProducer") final ApplicationEventListeningMessageProducer producer) {
        return IntegrationFlow.from(producer)
            .filter(ResourceEvent.class, EventIntegrationConfiguration::isCompleteResourceEvent,
                filter -> filter.id("resourceEventFilter"))
            .channel(EventChannels.RESOURCE_EVENTS)
            .get();
    }

    /**
     * Document events without an identifier or an action cannot be processed; they are dropped
     * before they reach the handlers.
     *
     * @param producer the document event adapter
     * @return the flow from the adapter to {@value EventChannels#DOCUMENT_EVENTS}
     */
    @Bean
    public IntegrationFlow documentEventFlow(
            @Qualifier("documentEventProducer") final ApplicationEventListeningMessageProducer producer) {
        return IntegrationFlow.from(producer)
            .filter(DocumentEvent.class, EventIntegrationConfiguration::isCompleteDocumentEvent,
                filter -> filter.id("documentEventFilter"))
            .channel(EventChannels.DOCUMENT_EVENTS)
            .get();
    }

    /**
     * Subscribes every {@link ResourceEventHandler} and {@link DocumentEventHandler} bean to its
     * channel once all singletons exist, in {@link org.springframework.core.Ordered order}.
     * Handlers are optional: services that only publish (the platform) subscribe nothing.
     *
     * @param resourceEvents   the resource event channel
     * @param documentEvents   the document event channel
     * @param errors           the dead letter channel
     * @param resourceHandlers the resource event handlers
     * @param documentHandlers the document event handlers
     * @return the subscribing callback
     */
    @Bean
    public SmartInitializingSingleton eventHandlerSubscriptions(
            @Qualifier(EventChannels.RESOURCE_EVENTS) final SubscribableChannel resourceEvents,
            @Qualifier(EventChannels.DOCUMENT_EVENTS) final SubscribableChannel documentEvents,
            @Qualifier(EventChannels.HANDLER_ERRORS) final MessageChannel errors,
            final ObjectProvider<ResourceEventHandler> resourceHandlers,
            final ObjectProvider<DocumentEventHandler> documentHandlers) {
        return () -> {
            resourceHandlers.orderedStream().forEach(handler -> {
                resourceEvents.subscribe(new EventHandlerMessageHandler<>(ResourceEvent.class, handler::handle,
                    handler.getClass().getName(), errors));
                log.info("Subscribed resource event handler {}", handler.getClass().getName());
            });
            documentHandlers.orderedStream().forEach(handler -> {
                documentEvents.subscribe(new EventHandlerMessageHandler<>(DocumentEvent.class, handler::handle,
                    handler.getClass().getName(), errors));
                log.info("Subscribed document event handler {}", handler.getClass().getName());
            });
        };
    }

    /**
     * @param event a resource event
     * @return whether the event has a resource and an action
     */
    static boolean isCompleteResourceEvent(final ResourceEvent event) {
        final boolean complete = event.getResource() != null && event.getAction() != null;
        if (!complete) {
            log.warn("Dropping resource event {} without resource or action from {}", event.getId(),
                event.getOriginService());
        } else {
            log.debug("Received {} for {} from {}", event.getAction(), event.getResource().getPath(),
                event.getOriginService());
        }
        return complete;
    }

    /**
     * @param event a document event
     * @return whether the event has a document identifier and an action
     */
    static boolean isCompleteDocumentEvent(final DocumentEvent event) {
        final boolean complete = event.documentId() != null && event.getAction() != null;
        if (!complete) {
            log.warn("Dropping document event {} without id or action from {}", event.getId(),
                event.getOriginService());
        } else {
            log.debug("Received {} for {} from {}", event.getAction(), event.documentId(), event.getOriginService());
        }
        return complete;
    }

}
