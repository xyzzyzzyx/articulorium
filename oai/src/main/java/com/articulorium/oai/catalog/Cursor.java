package com.articulorium.oai.catalog;

import java.time.Instant;

/**
 * Where a list continues: {@code offset} results past the first resource modified at
 * {@code anchor}, in the order of modification time and path.
 *
 * <p>Invariant: the first {@code offset} results modified at or after {@code anchor} have already
 * been covered (answered, filtered out, or hidden by access control). Continuing after a time
 * rather than after a running count keeps the position stable while the repository changes.</p>
 *
 * @param anchor the modification time, {@code null} for the start of the list
 * @param offset the number of results at or after the anchor already covered
 */
public record Cursor(Instant anchor, int offset) {

    /** The start of a list. */
    public static final Cursor START = new Cursor(null, 0);

}
