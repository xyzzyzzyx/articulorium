package com.articulorium.consul;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.cloud.consul.ConsulClient;

/**
 * Makes the Spring Cloud Consul client usable in GraalVM native images.
 *
 * <p>{@link ConsulClient} is an HTTP interface whose requests and responses are bound with
 * Jackson. Spring only derives the native image metadata of such an interface from a bean
 * definition, and the client is not one: with {@code spring.config.import=consul:} it is
 * registered as a ready-made singleton. A native image then lacks the client's methods (and their
 * parameter names) and every model class, so registration writes an empty service and discovery
 * reads none.</p>
 *
 * <p>The hints are those Spring would have derived itself: the exchange methods, and the binding
 * of the types they send and return. Contributed through {@code META-INF/spring/aot.factories}.</p>
 */
public class ConsulRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, ConsulClient.class);
    }

}
