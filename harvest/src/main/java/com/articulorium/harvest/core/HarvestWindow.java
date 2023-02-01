package com.articulorium.harvest.core;

import java.time.Instant;

/**
 * Which changes a harvest asks a source for.
 *
 * @param from the earliest change to harvest (inclusive), or {@code null} for every record
 */
public record HarvestWindow(Instant from) {

    /** Every record. */
    public static final HarvestWindow ALL = new HarvestWindow(null);

    /**
     * Tells whether this window asks only for changes.
     *
     * @return {@code true} when {@link #from()} is set
     */
    public boolean incremental() {
        return from != null;
    }

}
