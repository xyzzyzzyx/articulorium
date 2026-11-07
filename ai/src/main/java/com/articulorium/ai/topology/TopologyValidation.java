package com.articulorium.ai.topology;

import java.util.List;

/**
 * What the topology API says about a draft.
 *
 * @param valid  whether it can be saved as it is
 * @param errors the problems found
 * @param exists whether a topology with its identifier exists already
 */
public record TopologyValidation(Boolean valid, List<String> errors, Boolean exists) {

    /**
     * Creates the validation with an immutable list.
     *
     * @param valid  validity
     * @param errors problems
     * @param exists identifier taken
     */
    public TopologyValidation {
        valid = Boolean.TRUE.equals(valid);
        exists = Boolean.TRUE.equals(exists);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

}
