package com.articulorum.transform.iiif;

/**
 * Thrown when an IIIF export cannot be produced; carries the HTTP status to answer with.
 */
public class IiifException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates the exception.
     *
     * @param status  the HTTP status
     * @param message what failed
     * @param cause   the underlying failure, may be {@code null}
     */
    public IiifException(final int status, final String message, final Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** @return the HTTP status */
    public int getStatus() {
        return status;
    }

}
