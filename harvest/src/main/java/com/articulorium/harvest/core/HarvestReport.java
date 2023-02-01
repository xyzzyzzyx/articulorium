package com.articulorium.harvest.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What a harvest did.
 *
 * @param sourceId   the source
 * @param type       the source's type
 * @param mode       the requested mode
 * @param outcome    how it ended
 * @param startedAt  when it started
 * @param finishedAt when it ended
 * @param from       the start of the incremental window, or {@code null} for a full harvest
 * @param checkpoint the checkpoint saved for the next incremental harvest, or {@code null} when it
 *                   did not move
 * @param pages      pages fetched
 * @param harvested  records listed by the source (ingested, skipped or failed)
 * @param created    copies created
 * @param updated    copies replaced
 * @param deleted    copies removed
 * @param skipped    deleted records that had no copy
 * @param failed     records that failed (listed or ingested)
 * @param failures   the first failures (see {@link HarvestPolicy#maxReportedFailures()})
 * @param message    why the harvest failed or was cancelled, or {@code null}
 */
public record HarvestReport(String sourceId, String type, HarvestMode mode, HarvestOutcome outcome,
        Instant startedAt, Instant finishedAt, Instant from, Instant checkpoint, int pages, long harvested,
        long created, long updated, long deleted, long skipped, long failed, List<RecordFailure> failures,
        String message) {

    /**
     * Creates a report.
     *
     * @param sourceId   the source
     * @param type       the type
     * @param mode       the mode
     * @param outcome    the outcome
     * @param startedAt  the start
     * @param finishedAt the end
     * @param from       the window start
     * @param checkpoint the saved checkpoint
     * @param pages      the pages
     * @param harvested  the listed records
     * @param created    the created copies
     * @param updated    the replaced copies
     * @param deleted    the removed copies
     * @param skipped    the skipped deletions
     * @param failed     the failed records
     * @param failures   the listed failures
     * @param message    the message
     */
    public HarvestReport {
        failures = List.copyOf(failures);
    }

    /**
     * Returns how long the harvest took.
     *
     * @return the duration
     */
    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

}
