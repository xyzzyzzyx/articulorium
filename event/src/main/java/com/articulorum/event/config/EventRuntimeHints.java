package com.articulorum.event.config;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventAction;
import com.articulorum.event.ResourceEvent;

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
