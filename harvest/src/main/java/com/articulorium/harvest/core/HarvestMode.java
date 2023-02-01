package com.articulorium.harvest.core;

/**
 * How much of a source a harvest reads.
 */
public enum HarvestMode {

    /**
     * The records changed since the source's checkpoint, or every record before the first complete
     * harvest or when the source cannot list changes.
     */
    INCREMENTAL,

    /** Every record, whatever the checkpoint. */
    FULL

}
