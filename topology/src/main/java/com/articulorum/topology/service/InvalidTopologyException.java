package com.articulorum.topology.service;

import java.util.List;

/**
 * Thrown when a topology definition or a build request is invalid; answered with 400.
 */
public class InvalidTopologyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    /**
     * Creates the exception.
     *
     * @param errors the problems found
     */
    public InvalidTopologyException(final List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /** @return the problems found */
    public List<String> getErrors() {
        return errors;
    }

}
