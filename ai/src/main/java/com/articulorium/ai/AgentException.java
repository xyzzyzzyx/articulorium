package com.articulorium.ai;

/**
 * Why the agent could not answer. Every reason maps to one HTTP status, and the user interface
 * explains each one ({@code reason} of the problem document).
 */
public class AgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why
     * @param message a diagnostic for operators (in English, without repository content)
     */
    public AgentException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Creates the exception.
     *
     * @param reason  why
     * @param message a diagnostic for operators
     * @param cause   the cause
     */
    public AgentException(final Reason reason, final String message, final Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Returns why.
     *
     * @return the reason
     */
    public Reason getReason() {
        return reason;
    }

    /** Why the agent could not answer, with the HTTP status of each reason. */
    public enum Reason {

        /** The request is malformed. */
        INVALID_REQUEST(400),

        /** The caller is anonymous or its credentials were refused. */
        UNAUTHENTICATED(401),

        /** The caller may not change the resource the suggestion is about. */
        FORBIDDEN(403),

        /** The resource does not exist (or the caller may not know it does). */
        NOT_FOUND(404),

        /** The binary is larger than the agent reads. */
        TOO_LARGE(413),

        /** The resource is not something the capability handles (a binary of another type, a container that is no collection). */
        UNSUPPORTED(415),

        /** Too many model calls are running; try again later. */
        BUSY(429),

        /** The model failed (an error, a timeout, a refusal). */
        MODEL_ERROR(502),

        /** The model answered something that is not a usable suggestion. */
        INVALID_OUTPUT(502),

        /** The agent is turned off ({@code articulorium.ai.enabled=false}). */
        DISABLED(503),

        /** The capability is turned off. */
        CAPABILITY_DISABLED(503),

        /** No chat model is configured ({@code spring.ai.model.chat=none} or no provider). */
        MODEL_UNAVAILABLE(503),

        /** The platform or the topology service cannot be reached. */
        PLATFORM_UNAVAILABLE(503);

        private final int status;

        Reason(final int status) {
            this.status = status;
        }

        /**
         * Returns the HTTP status.
         *
         * @return the status
         */
        public int status() {
            return status;
        }

    }

}
