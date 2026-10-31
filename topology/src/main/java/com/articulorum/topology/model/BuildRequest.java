package com.articulorum.topology.model;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * A request to build the PCDM resources of a topology in the repository.
 *
 * @param parent   path of the container to build in (for example a collection), empty for the
 *                 root
 * @param title    the root resource's {@code dcterms:title}
 * @param slug     the root resource's name, {@code null} to derive it from the title
 * @param metadata values for the root node's metadata fields, by predicate IRI
 * @param counts   how many children to create per node key, overriding each rule's
 *                 {@link ChildRule#initial()}
 */
public record BuildRequest(String parent, @NotBlank String title, String slug, Map<String, List<String>> metadata,
        Map<String, Integer> counts) {

    /**
     * Creates the request with empty maps instead of {@code null}.
     *
     * @param parent   the parent container path
     * @param title    the title
     * @param slug     the slug
     * @param metadata metadata values
     * @param counts   child counts
     */
    public BuildRequest {
        parent = parent == null ? "" : parent;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

}
