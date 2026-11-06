package com.articulorium.topology.service;

/**
 * Thrown when a topology does not exist; answered with 404.
 */
public class TopologyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String id;

    /**
     * Creates the exception.
     *
     * @param id the unknown identifier
     */
    public TopologyNotFoundException(final String id) {
        super("No topology " + id);
        this.id = id;
    }

    /**
     * The identifier that matched no topology.
     *
     * @return the identifier
     */
    public String getId() {
        return id;
    }

}
