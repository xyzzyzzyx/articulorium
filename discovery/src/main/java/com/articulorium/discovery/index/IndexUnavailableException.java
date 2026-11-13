package com.articulorium.discovery.index;

/**
 * The search index could not answer. The message is safe to show: it never carries the backend's
 * own diagnostics.
 */
public class IndexUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed, safe to show to callers
     */
    public IndexUnavailableException(final String message) {
        super(message);
    }

}
