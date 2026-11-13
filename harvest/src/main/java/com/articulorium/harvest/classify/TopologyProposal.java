package com.articulorium.harvest.classify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.JsonNode;

/**
 * The topology the AI agent suggests for some material, as its {@code /topology-suggestions} API
 * answers: an existing topology, or the draft of a new one with what the topology API would still
 * refuse in it. The draft is kept as the JSON document the topology API reads.
 *
 * @param recommendation {@code EXISTING} or {@code NEW}
 * @param existing       the existing topology
 * @param draft          the draft of a new topology
 * @param errors         what the topology API refuses in the draft; empty for a valid draft
 * @param rationale      why
 * @param confidence     how sure the agent is, 0 to 1
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TopologyProposal(String recommendation, Existing existing, JsonNode draft, List<String> errors,
        String rationale, Double confidence) {

    /** An existing topology fits. */
    public static final String EXISTING = "EXISTING";

    /** A new topology is drafted. */
    public static final String NEW = "NEW";

    /**
     * Creates the proposal with an empty list instead of {@code null}.
     *
     * @param recommendation existing or new
     * @param existing       the existing topology
     * @param draft          the draft
     * @param errors         the draft's problems
     * @param rationale      why
     * @param confidence     how sure
     */
    public TopologyProposal {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * An existing topology.
     *
     * @param id   its id
     * @param name its name
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Existing(String id, String name) {
    }

}
