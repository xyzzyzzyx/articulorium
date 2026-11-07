package com.articulorium.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A component that acts as an agent while it is turned on: an ingest step, an enrichment service,
 * an AI capability. It describes itself, so the registry lists exactly the agents an application
 * runs, without a second list to keep in step with the configuration.
 *
 * <p>Plain Java like the rest of the library. The Spring Boot integration
 * ({@code agent-spring-boot-starter}) adds the agents of every {@code AgentContributor} bean to the
 * {@link AgentRegistry}.</p>
 */
@FunctionalInterface
public interface AgentContributor {

    /**
     * Returns the agent this component acts as.
     *
     * @return the agent, or empty while the component is turned off
     */
    Optional<Agent> agent();

    /**
     * Collects the agents of components that are turned on, in the order given.
     *
     * @param contributors the components
     * @return their agents
     * @throws NullPointerException when the collection, one of its components or an answer is
     *                              {@code null}
     */
    static List<Agent> agentsOf(final Collection<? extends AgentContributor> contributors) {
        Objects.requireNonNull(contributors, "contributors");
        final List<Agent> agents = new ArrayList<>(contributors.size());
        for (final AgentContributor contributor : contributors) {
            Objects.requireNonNull(contributor, "contributor");
            Objects.requireNonNull(contributor.agent(), "agent").ifPresent(agents::add);
        }
        return agents;
    }

}
