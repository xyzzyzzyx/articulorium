package com.articulorium.consul;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.aot.AotServices;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.model.http.agent.NewService;
import org.springframework.cloud.consul.model.http.agent.Service;
import org.springframework.cloud.consul.model.http.catalog.CatalogService;
import org.springframework.cloud.consul.model.http.catalog.Node;
import org.springframework.cloud.consul.model.http.event.Event;
import org.springframework.cloud.consul.model.http.health.Check;
import org.springframework.cloud.consul.model.http.health.HealthService;
import org.springframework.cloud.consul.model.http.kv.GetValue;

/**
 * Tests that a native image can call the Consul agent and bind what it sends and returns.
 */
class ConsulRuntimeHintsTest {

    private static RuntimeHints hints() {
        final RuntimeHints hints = new RuntimeHints();
        new ConsulRuntimeHints().registerHints(hints, ConsulRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    @Test
    void everyExchangeMethodIsInvocable() {
        final RuntimeHints hints = hints();

        final List<Method> methods = Arrays.asList(ConsulClient.class.getDeclaredMethods());

        // parameter names (@PathVariable, @RequestParam) are only kept for registered methods
        assertThat(methods).isNotEmpty().allSatisfy(method -> assertThat(
            RuntimeHintsPredicates.reflection().onMethodInvocation(method)).accepts(hints));
    }

    @Test
    void registrationAndDiscoveryModelsBindWithJackson() throws NoSuchMethodException {
        final RuntimeHints hints = hints();

        // service registration (request body), discovery (health services), configuration (KV)
        assertThat(List.of(NewService.class, NewService.Check.class, HealthService.class,
            HealthService.Service.class, HealthService.Node.class, Check.class, Service.class, Node.class,
            CatalogService.class, GetValue.class, Event.class)).allSatisfy(type -> assertThat(
                RuntimeHintsPredicates.reflection().onType(type)).accepts(hints));
        assertThat(RuntimeHintsPredicates.reflection()
            .onMethodInvocation(NewService.class.getMethod("getName"))).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
            .onMethodInvocation(HealthService.class.getMethod("setService", HealthService.Service.class)))
            .accepts(hints);
    }

    @Test
    void isContributedToEveryApplicationOnTheClassPath() {
        assertThat(AotServices.factories().load(RuntimeHintsRegistrar.class))
            .hasAtLeastOneElementOfType(ConsulRuntimeHints.class);
    }

}
