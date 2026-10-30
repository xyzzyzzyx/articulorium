package com.articulorum.platform.storage;

import java.io.IOException;

/**
 * Thrown when a binary store holds nothing under a requested key.
 */
public class BinaryNotFoundException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param key the missing key
     */
    public BinaryNotFoundException(final String key) {
        super("No binary stored under key " + key);
    }

}
