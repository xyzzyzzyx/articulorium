package com.articulorium.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentContributor}.
 */
class AgentContributorTest {

    private final Agent owl = new Agent("owl", "read owl");

    private final Agent lark = new Agent("lark", "sing");

    @Test
    void collectsTheAgentsOfComponentsThatAreOnInOrder() {
        final AgentContributor off = Optional::empty;
        final List<Agent> agents = AgentContributor.agentsOf(List.of(() -> Optional.of(lark), off,
            () -> Optional.of(owl)));
        assertEquals(List.of(lark, owl), agents);
    }

    @Test
    void feedsARegistry() {
        final AgentRegistry registry =
            new AgentRegistry(AgentContributor.agentsOf(List.of(() -> Optional.of(owl))));
        assertEquals(Optional.of(owl), registry.find("owl"));
    }

    @Test
    void refusesMissingComponentsAndAnswers() {
        assertThrows(NullPointerException.class, () -> AgentContributor.agentsOf(null));
        assertThrows(NullPointerException.class,
            () -> AgentContributor.agentsOf(Arrays.asList((AgentContributor) null)));
        assertThrows(NullPointerException.class,
            () -> AgentContributor.agentsOf(List.of(() -> null)));
    }

}
