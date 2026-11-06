package com.articulorium.harvest.service;

/**
 * A harvest request that cannot be honoured.
 */
public class HarvestRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why
     * @param message the message
     */
    public HarvestRequestException(final Reason reason, final String message) {
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

        /** No source has the identifier. */
        UNKNOWN_SOURCE,

        /** The source is disabled. */
        DISABLED,

        /** The source is being harvested already. */
        RUNNING,

        /** The source is not being harvested. */
        NOT_RUNNING,

        /** The request itself is invalid. */
        INVALID

    }

}
