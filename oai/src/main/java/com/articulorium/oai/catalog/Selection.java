package com.articulorium.oai.catalog;

import java.time.Instant;

/**
 * Which records a list request selects.
 *
 * @param setPath        the path of the set (community or collection) whose records are
 *                       selected, or {@code null} for all records
 * @param from           the earliest modification time selected (inclusive), may be {@code null}
 * @param untilExclusive the first modification time no longer selected, may be {@code null}
 */
public record Selection(String setPath, Instant from, Instant untilExclusive) {

    /** Every record. */
    public static final Selection ALL = new Selection(null, null, null);

}
