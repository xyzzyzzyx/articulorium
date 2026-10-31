package com.articulorum.transform.spi;

/**
 * Thrown when a resource cannot be transformed, or a transformer is unknown or misconfigured.
 */
public class TransformationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the description of the problem
     */
    public TransformationException(final String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the description of the problem
     * @param cause   the underlying failure
     */
    public TransformationException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
