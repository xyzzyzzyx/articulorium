package com.articulorium.agent.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import com.articulorium.agent.Agent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentProperties}.
 */
class AgentPropertiesTest {

    @Test
    void hasNoAgentsByDefault() {
        final AgentProperties properties = new AgentProperties(true, null);
        assertThat(properties.definitions()).isEmpty();
        assertThat(properties.toAgents()).isEmpty();
    }

    @Test
    void turnsDefinitionsIntoAgentsInOrder() {
        final Map<String, AgentProperties.Definition> definitions = new LinkedHashMap<>();
        definitions.put("owl", new AgentProperties.Definition("read owl"));
        definitions.put("lark", null);
        final AgentProperties properties = new AgentProperties(true, definitions);
        assertThat(properties.toAgents())
            .containsExactly(new Agent("owl", "read owl"), new Agent("lark", ""));
    }

    @Test
    void copiesTheDefinitions() {
        final Map<String, AgentProperties.Definition> definitions = new LinkedHashMap<>();
        final AgentProperties properties = new AgentProperties(true, definitions);
        definitions.put("owl", new AgentProperties.Definition("read owl"));
        assertThat(properties.definitions()).isEmpty();
        assertThatThrownBy(() -> properties.definitions().put("x", null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesBlankNames() {
        final AgentProperties properties = new AgentProperties(true,
            Map.of(" ", new AgentProperties.Definition("nothing")));
        assertThatThrownBy(properties::toAgents).isInstanceOf(IllegalArgumentException.class);
    }

}
