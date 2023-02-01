package com.articulorium.discovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests move forward (caches with a time to live).
 */
public final class MutableClock extends Clock {

    private Instant now;

    /**
     * Creates the clock.
     *
     * @param now the initial instant
     */
    public MutableClock(final Instant now) {
        this.now = now;
    }

    /**
     * Moves the clock forward.
     *
     * @param duration how far
     */
    public void advance(final Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

}
