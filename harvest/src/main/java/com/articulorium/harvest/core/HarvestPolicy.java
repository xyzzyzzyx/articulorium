package com.articulorium.harvest.core;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link Harvester} copes with failures and moves checkpoints.
 *
 * @param maxAttempts         attempts of one page fetch or record ingest before giving up (at least 1)
 * @param backoff             delay before the first retry; doubled for every further retry
 * @param maxBackoff          longest delay between retries (also caps {@code Retry-After})
 * @param maxFailures         record failures after which a harvest stops (0 for no limit)
 * @param maxReportedFailures record failures listed in a report (the rest are only counted)
 * @param overlap             how far before the harvest's start the next incremental harvest
 *                            starts, to cover clock differences and changes made while harvesting
 */
public record HarvestPolicy(int maxAttempts, Duration backoff, Duration maxBackoff, int maxFailures,
        int maxReportedFailures, Duration overlap) {

    /** Three attempts, 2 s doubling up to 1 min, stop after 100 failures, 5 min overlap. */
    public static final HarvestPolicy DEFAULT = new HarvestPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(1),
        100, 100, Duration.ofMinutes(5));

    /**
     * Creates a policy.
     *
     * @param maxAttempts         the attempts
     * @param backoff             the first delay
     * @param maxBackoff          the longest delay
     * @param maxFailures         the failure limit
     * @param maxReportedFailures the listed failures
     * @param overlap             the overlap
     */
    public HarvestPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(overlap, "overlap");
        if (backoff.isNegative() || maxBackoff.isNegative() || overlap.isNegative()) {
            throw new IllegalArgumentException("Durations must not be negative");
        }
        if (maxFailures < 0 || maxReportedFailures < 0) {
            throw new IllegalArgumentException("Failure limits must not be negative");
        }
    }

    /**
     * Returns the delay before a retry.
     *
     * @param retry      the retry (1 for the first)
     * @param retryAfter the delay the other side asked for, may be {@code null}
     * @return the delay
     */
    public Duration delay(final int retry, final Duration retryAfter) {
        Duration delay = backoff;
        for (int i = 1; i < retry && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

}
