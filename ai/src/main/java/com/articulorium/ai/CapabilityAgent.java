package com.articulorium.ai;

import java.util.Optional;

import com.articulorium.agent.Agent;
import com.articulorium.agent.AgentContributor;
import com.articulorium.ai.config.AiProperties;

import org.springframework.core.Ordered;

/**
 * A capability of the AI agent as an Articulorium agent: while the agent and the capability are
 * turned on, it is listed in the agent registry (and {@code /actuator/info}) under its
 * {@link Capability#agentName() name}, and {@code GET /capabilities} offers it when a model is
 * configured. The registry is the one list of what the AI agent runs, in the order the
 * capabilities are declared.
 */
public abstract class CapabilityAgent implements AgentContributor, Ordered {

    private final Capability capability;

    private final AiProperties properties;

    /**
     * Creates the capability.
     *
     * @param capability which capability
     * @param properties the agent settings
     */
    protected CapabilityAgent(final Capability capability, final AiProperties properties) {
        this.capability = capability;
        this.properties = properties;
    }

    /**
     * Returns which capability this is.
     *
     * @return the capability
     */
    public final Capability capability() {
        return capability;
    }

    @Override
    public final int getOrder() {
        return capability.ordinal();
    }

    @Override
    public final Optional<Agent> agent() {
        return capability.enabled(properties) ? Optional.of(capability.toAgent()) : Optional.empty();
    }

}
