package com.articulorium.harvest.core;

/**
 * What ingesting a record did.
 */
public enum IngestAction {

    /** A new copy was stored. */
    CREATED,

    /** An existing copy was replaced. */
    UPDATED,

    /** The copy of a deleted record was removed. */
    DELETED,

    /** Nothing to do (a deleted record that was never ingested). */
    SKIPPED

}
