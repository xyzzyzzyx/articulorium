package com.articulorium.harvest.source;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.articulorium.harvest.core.HarvestException;

/**
 * A position in a listing ordered by modification time, for sources that page with offsets or page
 * numbers (DSpace discovery, Fedora search).
 *
 * <p>Offsets into the whole listing shift when records change while a harvest runs: a record
 * modified after it was read moves to the end of the listing, and every record behind it moves one
 * place forward, so one would be skipped. Keyset paging avoids that: every page asks for the records
 * modified at or after the time of the last record read ({@link #time()}) and skips those already
 * read at that very time ({@link #skip()} of them, or the {@link #seen()} ones). Only records sharing
 * one time stamp are paged by offset.</p>
 *
 * @param time the time to list from (inclusive), or {@code null} for the start of the listing
 * @param skip how many records of the listing from {@link #time()} were already read
 * @param seen identifiers already read at {@link #time()} (sources that page by page number)
 */
public record KeysetCursor(Instant time, int skip, Set<String> seen) {

    /**
     * Creates a cursor.
     *
     * @param time the time
     * @param skip the records to skip
     * @param seen the identifiers already read
     */
    public KeysetCursor {
        seen = Set.copyOf(seen);
    }

    /**
     * Starts a listing.
     *
     * @param from the earliest change, or {@code null} for every record
     * @return the first cursor
     */
    public static KeysetCursor start(final Instant from) {
        return new KeysetCursor(from, 0, Set.of());
    }

    /**
     * Computes the cursor after a page.
     *
     * @param read     how many records the page listed
     * @param times    the modification times of the listed records, in order (entries may be
     *                 {@code null} when a record has none)
     * @param ids      the identifiers of the listed records, in order
     * @param keepSeen whether to remember identifiers (page-number paging) or only count them
     * @return the next cursor
     */
    public KeysetCursor next(final int read, final List<Instant> times, final List<String> ids,
            final boolean keepSeen) {
        final Instant last = times.isEmpty() ? null : times.get(times.size() - 1);
        if (last == null || last.equals(time)) {
            // the whole page shares the current time (or has none): move on by offset
            final Set<String> stillSeen = new LinkedHashSet<>(seen);
            if (keepSeen) {
                ids.stream().filter(Objects::nonNull).forEach(stillSeen::add);
            }
            return new KeysetCursor(time, skip + read, stillSeen);
        }
        int atLast = 0;
        final Set<String> seenAtLast = new LinkedHashSet<>();
        for (int i = 0; i < times.size(); i++) {
            if (last.equals(times.get(i))) {
                atLast++;
                if (keepSeen && ids.get(i) != null) {
                    seenAtLast.add(ids.get(i));
                }
            }
        }
        return new KeysetCursor(last, atLast, seenAtLast);
    }

    /**
     * Encodes the cursor as an opaque string.
     *
     * @return the encoded cursor
     */
    public String encode() {
        final String text = (time == null ? "-" : time.toString()) + "\n" + skip + "\n" + String.join("\n", seen);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor.
     *
     * @param encoded the encoded cursor
     * @return the cursor
     * @throws HarvestException when the cursor is not one of ours
     */
    public static KeysetCursor decode(final String encoded) {
        try {
            final String[] lines = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                .split("\n", -1);
            final Instant time = "-".equals(lines[0]) ? null : Instant.parse(lines[0]);
            final Set<String> seen = new LinkedHashSet<>();
            for (int i = 2; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    seen.add(lines[i]);
                }
            }
            return new KeysetCursor(time, Integer.parseInt(lines[1]), seen);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | DateTimeParseException e) {
            throw HarvestException.fatal("Invalid cursor " + encoded, e);
        }
    }

}
