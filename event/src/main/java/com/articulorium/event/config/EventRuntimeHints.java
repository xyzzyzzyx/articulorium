package com.articulorium.event.config;

import com.articulorium.domain.BinaryContent;
import com.articulorium.domain.InteractionModel;
import com.articulorium.domain.LdpResource;
import com.articulorium.domain.TermKind;
import com.articulorium.domain.Triple;
import com.articulorium.event.DocumentEvent;
import com.articulorium.event.EventAction;
import com.articulorium.event.ResourceEvent;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image hints for the bus event payloads.
 *
 * <p>Spring Cloud Bus serialises events with Jackson, which needs reflective access to the event
 * classes and every type they reference. On the JVM this registrar has no effect.</p>
 */
public class EventRuntimeHints implements RuntimeHintsRegistrar {

    private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        bindingRegistrar.registerReflectionHints(hints.reflection(),
            ResourceEvent.class, DocumentEvent.class, EventAction.class,
            LdpResource.class, Triple.class, BinaryContent.class, InteractionModel.class, TermKind.class);
    }

}
