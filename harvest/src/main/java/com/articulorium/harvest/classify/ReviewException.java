package com.articulorium.harvest.classify;

/**
 * A review request that cannot be honoured, with its HTTP status and a reason code the user
 * interface explains.
 */
public class ReviewException extends RuntimeException {

    /** No source has the identifier. */
    public static final String UNKNOWN_SOURCE = "UNKNOWN_SOURCE";

    /** The record or its classification does not exist. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** The record was accepted already. */
    public static final String DECIDED = "DECIDED";

    /** The suggestion names no existing collection: the request must. */
    public static final String TARGET_REQUIRED = "TARGET_REQUIRED";

    /** The suggestion proposes a new topology: the request must name an existing one (or none). */
    public static final String TOPOLOGY_REQUIRED = "TOPOLOGY_REQUIRED";

    /** The record could not be placed (the collection or topology is wrong, or the platform refused). */
    public static final String NOT_PLACED = "NOT_PLACED";

    /** The platform refused the caller. */
    public static final String REFUSED = "REFUSED";

    /** The platform cannot be reached. */
    public static final String PLATFORM_UNAVAILABLE = "PLATFORM_UNAVAILABLE";

    private static final long serialVersionUID = 1L;

    private final int status;

    private final String reason;

    /**
     * Creates the exception.
     *
     * @param status  the HTTP status
     * @param reason  the reason code
     * @param message a diagnostic (English)
     */
    public ReviewException(final int status, final String reason, final String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the reason code.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }

}
