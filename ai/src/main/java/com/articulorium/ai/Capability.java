package com.articulorium.ai;

import com.articulorium.agent.Agent;
import com.articulorium.ai.config.AiProperties;

/**
 * What the AI agent can do. Each capability is implemented by a {@link CapabilityAgent}, listed in
 * the agent registry under its {@link #agentName() name} while it is on, and turned off on its own
 * with {@code articulorium.ai.<key>.enabled}.
 */
public enum Capability {

    /** Suggests a topology for some material: an existing one, or a draft of a new one. */
    TOPOLOGY("topology", "ai-topology-assistant", "Suggests a PCDM topology (existing or a reviewable draft) for some material"),

    /** Describes a binary: technical metadata from the file, descriptive metadata from the model. */
    MEDIA("media", "ai-media-describer", "Suggests metadata for images, videos and PDFs"),

    /** Models the topics of a community or collection from the texts of its items. */
    TOPICS("topics", "ai-topic-modeler", "Suggests the topics and a summary of a community or collection"),

    /** Suggests an overview, research areas and keywords for an academic (VIVO) profile. */
    PROFILES("profiles", "ai-profile-enhancer", "Suggests the overview, research areas and keywords of academic profiles"),

    /** Classifies a harvested record against the community structure and the topologies. */
    CLASSIFICATION("classification", "ai-harvest-classifier",
        "Suggests the collection and topology of harvested records");

    private final String key;

    private final String agentName;

    private final String description;

    Capability(final String key, final String agentName, final String description) {
        this.key = key;
        this.agentName = agentName;
        this.description = description;
    }

    /**
     * Returns the key used in settings and in the capabilities document.
     *
     * @return the key, for example {@code media}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the name of the agent registered for the capability.
     *
     * @return the agent name
     */
    public String agentName() {
        return agentName;
    }

    /**
     * Returns what the capability does.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    /**
     * Returns the Articulorium agent of the capability.
     *
     * @return the agent, named {@link #agentName()}
     */
    public Agent toAgent() {
        return new Agent(agentName, description);
    }

    /**
     * Returns the capability's settings.
     *
     * @param properties the agent settings
     * @return its settings
     */
    public AiProperties.Capability settings(final AiProperties properties) {
        return switch (this) {
            case TOPOLOGY -> properties.getTopology();
            case MEDIA -> properties.getMedia();
            case TOPICS -> properties.getTopics();
            case PROFILES -> properties.getProfiles();
            case CLASSIFICATION -> properties.getClassification();
        };
    }

    /**
     * Tells whether the capability is offered: the agent and the capability are both enabled.
     *
     * @param properties the agent settings
     * @return {@code true} when offered
     */
    public boolean enabled(final AiProperties properties) {
        return properties.isEnabled() && settings(properties).isEnabled();
    }

}
