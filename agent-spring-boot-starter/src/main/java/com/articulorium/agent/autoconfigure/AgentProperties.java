package com.articulorium.agent.autoconfigure;

import com.articulorium.agent.Agent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agents declared in configuration ({@code articulorium.agents.*}).
 *
 * <pre>
 * articulorium:
 *   agents:
 *     definitions:
 *       owl:
 *         description: read owl
 * </pre>
 *
 * <p>Definitions are keyed by agent name, so one agent can be added or overridden from any
 * property source (for example {@code ARTICULORIUM_AGENTS_DEFINITIONS_OWL_DESCRIPTION} or a Consul
 * key). Names with characters other than letters, digits and dashes need the bracket notation
 * ({@code articulorium.agents.definitions[ocr.v2].description}).</p>
 *
 * @param enabled     whether the integration is active (default {@code true})
 * @param definitions the declared agents by name, in declaration order
 */
@ConfigurationProperties(prefix = "articulorium.agents")
public record AgentProperties(@DefaultValue("true") boolean enabled,
        Map<String, Definition> definitions) {

    /**
     * Creates the properties.
     *
     * @param enabled     whether the integration is active
     * @param definitions the declared agents by name, may be {@code null}
     */
    public AgentProperties {
        definitions = definitions == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    /**
     * Returns the declared agents, in declaration order.
     *
     * @return the agents
     * @throws IllegalArgumentException when a definition has a blank name
     */
    public List<Agent> toAgents() {
        final List<Agent> agents = new ArrayList<>(definitions.size());
        definitions.forEach((name, definition) -> agents.add(
            new Agent(name, definition == null ? null : definition.description())));
        return agents;
    }

    /**
     * One declared agent.
     *
     * @param description what the agent does
     */
    public record Definition(String description) {
    }

}
