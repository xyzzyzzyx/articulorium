package com.articulorium.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentRegistry}.
 */
class AgentRegistryTest {

    private final Agent owl = new Agent("owl", "read owl");

    private final Agent lark = new Agent("lark", "sing");

    @Test
    void keepsRegistrationOrder() {
        final AgentRegistry registry = AgentRegistry.of(owl, lark);
        assertEquals(List.of(owl, lark), registry.getAgents());
        assertEquals(List.of("owl", "lark"), registry.getNames());
        assertEquals(2, registry.size());
    }

    @Test
    void findsAgentsByName() {
        final AgentRegistry registry = AgentRegistry.of(owl, lark);
        assertEquals(owl, registry.find("owl").orElseThrow());
        assertEquals(lark, registry.find(" lark ").orElseThrow());
        assertTrue(registry.find("crow").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void refusesDuplicateNames() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> AgentRegistry.of(owl, new Agent("owl", "other")));
        assertTrue(error.getMessage().contains("'owl'"));
    }

    @Test
    void refusesNulls() {
        assertThrows(NullPointerException.class, () -> new AgentRegistry(null));
        assertThrows(NullPointerException.class, () -> new AgentRegistry(Arrays.asList(owl, null)));
    }

    @Test
    void isImmutable() {
        final List<Agent> source = new ArrayList<>(List.of(owl));
        final AgentRegistry registry = new AgentRegistry(source);
        source.add(lark);
        assertEquals(1, registry.size());
        assertThrows(UnsupportedOperationException.class, () -> registry.getAgents().add(lark));
    }

    @Test
    void canBeEmpty() {
        final AgentRegistry registry = AgentRegistry.of();
        assertEquals(0, registry.size());
        assertEquals("AgentRegistry[]", registry.toString());
    }

}
