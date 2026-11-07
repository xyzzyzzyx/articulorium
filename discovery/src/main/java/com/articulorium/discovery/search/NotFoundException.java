package com.articulorium.discovery.search;

/**
 * A resource that does not exist, is not part of the discovery layer, or that the visitor may not
 * read: the three are not told apart, so private resources do not reveal their existence.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param path the requested path
     */
    public NotFoundException(final String path) {
        super("No resource /" + path);
    }

}
