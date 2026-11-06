package com.articulorium.harvest.core;

import java.time.Instant;
import java.util.List;

/**
 * One page of a harvest.
 *
 * @param records      the records read and mapped successfully
 * @param failures     the records the source listed but could not read or map; they are reported,
 *                     never dropped silently
 * @param nextCursor   the cursor of the next page, or {@code null} when this is the last page
 * @param responseTime the time of the source's own clock when it answered, when the protocol says
 *                     (OAI-PMH {@code responseDate}); used as the next incremental checkpoint
 */
public record HarvestPage(List<HarvestedRecord> records, List<RecordFailure> failures, String nextCursor,
        Instant responseTime) {

    /**
     * Creates a page.
     *
     * @param records      the records
     * @param failures     the failures
     * @param nextCursor   the next cursor, may be {@code null}
     * @param responseTime the source's time, may be {@code null}
     */
    public HarvestPage {
        records = List.copyOf(records);
        failures = List.copyOf(failures);
    }

    /**
     * A page of records without failures.
     *
     * @param records    the records
     * @param nextCursor the next cursor, may be {@code null}
     * @return the page
     */
    public static HarvestPage of(final List<HarvestedRecord> records, final String nextCursor) {
        return new HarvestPage(records, List.of(), nextCursor, null);
    }

    /**
     * The last page of a harvest without records.
     *
     * @return the empty page
     */
    public static HarvestPage empty() {
        return new HarvestPage(List.of(), List.of(), null, null);
    }

    /**
     * Tells whether another page follows.
     *
     * @return {@code true} when {@link #nextCursor()} is set
     */
    public boolean hasNext() {
        return nextCursor != null;
    }

}
