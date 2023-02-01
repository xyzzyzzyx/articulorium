package com.articulorium.harvest.core;

/**
 * Observes a harvest while it runs (progress, metrics). Called on the harvesting thread; must not
 * throw.
 */
public interface HarvestListener {

    /** A listener that ignores everything. */
    HarvestListener NONE = new HarvestListener() {
    };

    /**
     * A page was fetched.
     *
     * @param page the page
     */
    default void pageFetched(final HarvestPage page) {
        // ignored
    }

    /**
     * A record was ingested.
     *
     * @param record the record
     * @param action what ingest did
     */
    default void recordIngested(final HarvestedRecord record, final IngestAction action) {
        // ignored
    }

    /**
     * A record failed.
     *
     * @param failure the failure
     */
    default void recordFailed(final RecordFailure failure) {
        // ignored
    }

}
