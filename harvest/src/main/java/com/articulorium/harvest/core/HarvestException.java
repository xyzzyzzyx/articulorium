package com.articulorium.harvest.core;

import java.time.Duration;

/**
 * A harvest step that failed: a page that could not be fetched from a source, or a record that
 * could not be ingested.
 *
 * <p>{@link #isRecoverable() Recoverable} failures (network errors, timeouts, 5xx, 429) are
 * retried by the {@link Harvester}; the others (bad configuration, refused credentials, protocol
 * errors) are not. Messages are shown to operators and must not contain credentials.</p>
 */
public class HarvestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean recoverable;

    private final transient Duration retryAfter;

    /**
     * Creates a failure.
     *
     * @param message     what failed
     * @param recoverable whether retrying may help
     * @param retryAfter  how long the other side asked to wait before retrying, may be {@code null}
     * @param cause       the cause, may be {@code null}
     */
    public HarvestException(final String message, final boolean recoverable, final Duration retryAfter,
            final Throwable cause) {
        super(message, cause);
        this.recoverable = recoverable;
        this.retryAfter = retryAfter;
    }

    /**
     * A failure retrying may cure.
     *
     * @param message what failed
     * @param cause   the cause, may be {@code null}
     * @return the exception
     */
    public static HarvestException recoverable(final String message, final Throwable cause) {
        return new HarvestException(message, true, null, cause);
    }

    /**
     * A failure retrying will not cure.
     *
     * @param message what failed
     * @param cause   the cause, may be {@code null}
     * @return the exception
     */
    public static HarvestException fatal(final String message, final Throwable cause) {
        return new HarvestException(message, false, null, cause);
    }

    /**
     * Tells whether retrying may help.
     *
     * @return {@code true} for transient failures
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * Returns how long the other side asked to wait before retrying.
     *
     * @return the delay, or {@code null}
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

}
