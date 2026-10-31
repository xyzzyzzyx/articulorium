package com.articulorum.topology.platform;

/**
 * Thrown when the platform refuses or fails a request; carries the platform's status so that the
 * topology API can answer with it (for example 401/403 from Web Access Control).
 */
public class PlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates the exception.
     *
     * @param status  the platform's HTTP status, or 502 when it could not be reached
     * @param message what failed
     * @param cause   the underlying failure
     */
    public PlatformException(final int status, final String message, final Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** @return the platform's HTTP status */
    public int getStatus() {
        return status;
    }

}
