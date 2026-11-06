package com.articulorium.agent;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The agents an application knows, looked up by name.
 *
 * <p>Plain Java: create one directly ({@code AgentRegistry.of(owl, lark)}) or let the Spring Boot
 * integration ({@code agent-spring-boot-starter}) build it from {@link Agent} beans and
 * configuration. Registries are immutable, keep the order in which agents were given and refuse
 * two agents with the same name.</p>
 */
public final class AgentRegistry {

    private final Map<String, Agent> agents;

    /**
     * Creates a registry.
     *
     * @param agents the agents, in the order they are listed
     * @throws IllegalArgumentException when two agents share a name
     * @throws NullPointerException     when the collection or one of its agents is {@code null}
     */
    public AgentRegistry(final Collection<Agent> agents) {
        Objects.requireNonNull(agents, "agents");
        final Map<String, Agent> byName = new LinkedHashMap<>();
        for (final Agent agent : agents) {
            Objects.requireNonNull(agent, "agent");
            if (byName.putIfAbsent(agent.getName(), agent) != null) {
                throw new IllegalArgumentException(
                    "Two agents are named '" + agent.getName() + "'");
            }
        }
        this.agents = Collections.unmodifiableMap(byName);
    }

    /**
     * Creates a registry of the given agents.
     *
     * @param agents the agents, in the order they are listed
     * @return the registry
     * @throws IllegalArgumentException when two agents share a name
     */
    public static AgentRegistry of(final Agent... agents) {
        return new AgentRegistry(List.of(agents));
    }

    /**
     * Returns every agent, in registration order.
     *
     * @return an unmodifiable list of the agents
     */
    public List<Agent> getAgents() {
        return List.copyOf(agents.values());
    }

    /**
     * Returns the names of the agents, in registration order.
     *
     * @return an unmodifiable list of names
     */
    public List<String> getNames() {
        return List.copyOf(agents.keySet());
    }

    /**
     * Looks an agent up by name.
     *
     * @param name the name of the agent; surrounding white space is ignored
     * @return the agent, or empty when no agent has that name
     */
    public Optional<Agent> find(final String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(agents.get(name.trim()));
    }

    /**
     * Returns the number of agents.
     *
     * @return the number of agents
     */
    public int size() {
        return agents.size();
    }

    @Override
    public String toString() {
        return "AgentRegistry" + agents.keySet();
    }

}
