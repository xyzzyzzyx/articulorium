package com.articulorium.event.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorium.event.DocumentEvent;
import com.articulorium.event.EventAction;
import com.articulorium.event.EventDestinations;
import com.articulorium.event.handler.DocumentEventHandler;
import com.articulorium.event.listener.DocumentEventListener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Unit tests for {@link DocumentEventListener}.
 */
class DocumentEventListenerTest {

    @Test
    void dispatchesToHandlersAndSurvivesFailures() {
        final List<String> ids = new ArrayList<>();
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("failing", (DocumentEventHandler) e -> {
            throw new IllegalStateException("boom");
        });
        factory.registerSingleton("working", (DocumentEventHandler) e -> ids.add(e.documentId()));
        final Map<String, Collection<?>> document = Map.of(DocumentEvent.ID_FIELD, List.of("http://h/a"));

        new DocumentEventListener(factory.getBeanProvider(DocumentEventHandler.class))
            .onApplicationEvent(new DocumentEvent(this, "transform:1", EventDestinations.all(), document,
                EventAction.UPDATED));

        assertThat(ids).containsExactly("http://h/a");
    }

}
