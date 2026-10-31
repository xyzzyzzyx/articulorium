package com.articulorum.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Agent}.
 */
class AgentTest {

    @Test
    void keepsTrimmedNameAndDescription() {
        final Agent agent = new Agent("  owl ", " read owl ");
        assertEquals("owl", agent.getName());
        assertEquals("read owl", agent.getDescription());
    }

    @Test
    void descriptionDefaultsToEmpty() {
        assertEquals("", new Agent("owl", null).getDescription());
    }

    @Test
    void requiresAName() {
        assertThrows(IllegalArgumentException.class, () -> new Agent(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Agent("  ", "x"));
    }

    @Test
    void isAValueObject() {
        final Agent agent = new Agent("owl", "read owl");
        assertEquals(agent, new Agent("owl", "read owl"));
        assertEquals(agent.hashCode(), new Agent("owl", "read owl").hashCode());
        assertNotEquals(agent, new Agent("owl", "write owl"));
        assertNotEquals(agent, new Agent("lark", "read owl"));
        assertNotEquals(agent, "owl");
        assertTrue(agent.toString().contains("owl"));
    }

}
