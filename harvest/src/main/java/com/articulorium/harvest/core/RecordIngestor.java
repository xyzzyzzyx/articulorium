package com.articulorium.harvest.core;

/**
 * Where harvested records go: the ingest half of a harvest, independent of any source.
 *
 * <p>Ingest must be idempotent: the same record may be ingested again (overlapping incremental
 * windows, retried pages, a harvest restarted after a failure) and must then replace its earlier
 * copy rather than add another.</p>
 */
public interface RecordIngestor {

    /**
     * Prepares the ingest of a source's records (creates the containers they go to). Called once at
     * the start of every harvest.
     *
     * @param sourceId the source identifier
     * @throws HarvestException when the target cannot be prepared
     */
    default void begin(final String sourceId) {
        // nothing to prepare
    }

    /**
     * Stores, replaces or (for {@link HarvestedRecord#deleted() deleted} records) removes the copy of
     * a record.
     *
     * @param sourceId the source identifier
     * @param record   the record
     * @return what happened
     * @throws HarvestException when the record cannot be ingested; a non-recoverable failure fails
     *                          this record only, a recoverable one is retried and then aborts the
     *                          harvest (the target is likely unavailable)
     */
    IngestAction ingest(String sourceId, HarvestedRecord record);

}
