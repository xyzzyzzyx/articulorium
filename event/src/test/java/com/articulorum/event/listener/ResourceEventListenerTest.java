package com.articulorum.event.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import com.articulorum.event.handler.ResourceEventHandler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Unit tests for {@link ResourceEventListener}.
 */
class ResourceEventListenerTest {

    private ResourceEvent event() {
        return new ResourceEvent(this, "platform:1", EventDestinations.all(),
            new LdpResource("books", InteractionModel.BASIC_CONTAINER), EventAction.CREATED,
            "http://localhost:9000", List.of());
    }

    @Test
    void dispatchesToEveryHandler() {
        final List<String> calls = new ArrayList<>();
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("first", (ResourceEventHandler) e -> calls.add("first:" + e.getAction()));
        factory.registerSingleton("second", (ResourceEventHandler) e -> calls.add("second:" + e.getAction()));

        new ResourceEventListener(factory.getBeanProvider(ResourceEventHandler.class)).onApplicationEvent(event());

        assertThat(calls).containsExactlyInAnyOrder("first:CREATED", "second:CREATED");
    }

    @Test
    void failingHandlerDoesNotStopOthers() {
        final List<String> calls = new ArrayList<>();
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("failing", (ResourceEventHandler) e -> {
            throw new IllegalStateException("boom");
        });
        factory.registerSingleton("working", (ResourceEventHandler) e -> calls.add("working"));

        new ResourceEventListener(factory.getBeanProvider(ResourceEventHandler.class)).onApplicationEvent(event());

        assertThat(calls).containsExactly("working");
    }

    @Test
    void toleratesAbsenceOfHandlers() {
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new ResourceEventListener(factory.getBeanProvider(ResourceEventHandler.class)).onApplicationEvent(event());
    }

}
