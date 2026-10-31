package com.articulorum.agent;

import java.util.Objects;

/**
 * An agent known to Articulorum: a named actor with a short description of what it does.
 *
 * <p>The agent library is the foundation for automated actors (for example ingest or
 * enrichment agents) that work against the Linked Data Platform. Instances are immutable
 * value objects.</p>
 */
public final class Agent {

    private final String name;

    private final String description;

    /**
     * Creates an agent.
     *
     * @param name        the unique name of the agent, not blank
     * @param description what the agent does, may be {@code null}
     * @throws IllegalArgumentException when the name is {@code null} or blank
     */
    public Agent(final String name, final String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An agent needs a name");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description.trim();
    }

    /**
     * Returns the unique name of the agent.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns what the agent does.
     *
     * @return the description, empty when none was given
     */
    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Agent)) {
            return false;
        }
        final Agent that = (Agent) other;
        return name.equals(that.name) && description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description);
    }

    @Override
    public String toString() {
        return "Agent[name=" + name + ", description=" + description + "]";
    }

}
