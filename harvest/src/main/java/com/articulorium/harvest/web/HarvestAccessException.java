package com.articulorium.harvest.web;

/**
 * A harvest API request refused by access control, or whose access could not be decided.
 */
public class HarvestAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why
     * @param message the message, safe to show to the caller
     */
    public HarvestAccessException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns why the request was refused.
     *
     * @return the reason
     */
    public Reason getReason() {
        return reason;
    }

    /** Why a request was refused. */
    public enum Reason {

        /** No credentials, or credentials the platform refused, and anonymous access is not enough (401). */
        UNAUTHENTICATED,

        /** The caller is known but lacks the access mode (403). */
        FORBIDDEN,

        /** The platform could not decide (503). */
        UNAVAILABLE

    }

}
