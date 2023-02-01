package com.articulorium.topology.model;

import java.util.List;

/**
 * What saving a topology would say about it, without saving it.
 *
 * @param valid  whether it can be saved as a new custom topology as it is
 * @param errors the problems found (the same as a refused save lists)
 * @param exists whether a topology with its identifier exists already (built-in or custom)
 */
public record TopologyValidation(boolean valid, List<String> errors, boolean exists) {

    /**
     * Creates the validation with an immutable list.
     *
     * @param valid  validity
     * @param errors problems
     * @param exists identifier taken
     */
    public TopologyValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

}
