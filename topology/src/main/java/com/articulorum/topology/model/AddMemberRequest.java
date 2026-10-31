package com.articulorum.topology.model;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * A request to add one child to a resource built from a topology, for example another page to a
 * book.
 *
 * @param parent   path of the resource that receives the child (built from the topology)
 * @param nodeKey  the key of the child node, which must be a child of the parent's node
 * @param title    the child's title, {@code null} for "label position"
 * @param metadata values for the child's metadata fields, by predicate IRI
 */
public record AddMemberRequest(@NotBlank String parent, @NotBlank String nodeKey, String title,
        Map<String, List<String>> metadata) {

    /**
     * Creates the request with an empty map instead of {@code null}.
     *
     * @param parent   the parent path
     * @param nodeKey  the child node key
     * @param title    the title
     * @param metadata metadata values
     */
    public AddMemberRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

}
