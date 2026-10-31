package com.articulorum.topology.service;

/**
 * Thrown when a topology does not exist; answered with 404.
 */
public class TopologyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param id the unknown identifier
     */
    public TopologyNotFoundException(final String id) {
        super("No topology " + id);
    }

}
