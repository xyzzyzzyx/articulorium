package com.articulorium.harvest.core;

/**
 * How a harvest ended.
 */
public enum HarvestOutcome {

    /** Every listed record was ingested; the checkpoint moved forward. */
    COMPLETED,

    /**
     * The source was read to the end but some records failed; they are listed in the report and the
     * checkpoint did not move, so the next incremental harvest tries them again.
     */
    COMPLETED_WITH_ERRORS,

    /** The harvest stopped early (source or target unavailable, too many failures); the checkpoint did not move. */
    FAILED,

    /** The harvest was stopped on request; the checkpoint did not move. */
    CANCELLED

}
