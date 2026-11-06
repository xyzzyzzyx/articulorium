package com.articulorium.oai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * UTC datestamps of the protocol (section 3.3). The repository's granularity is seconds
 * ({@code YYYY-MM-DDThh:mm:ssZ}); harvesters may also send days ({@code YYYY-MM-DD}).
 */
public final class OaiDates {

    /** The granularity answered by Identify. */
    public static final String GRANULARITY = "YYYY-MM-DDThh:mm:ssZ";

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern SECOND = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    private OaiDates() {
        // utility class
    }

    /** The two granularities a harvester may use. */
    public enum Granularity {

        /** {@code YYYY-MM-DD}. */
        DAY(Duration.ofDays(1)),

        /** {@code YYYY-MM-DDThh:mm:ssZ}. */
        SECOND(Duration.ofSeconds(1));

        private final Duration length;

        Granularity(final Duration length) {
            this.length = length;
        }

        /** @return the length of one granule */
        public Duration length() {
            return length;
        }

    }

    /**
     * A datestamp argument.
     *
     * @param start       the first instant of the granule
     * @param granularity its granularity
     */
    public record Datestamp(Instant start, Granularity granularity) {

        /** @return the first instant after the granule, the exclusive upper bound of an {@code until} */
        public Instant end() {
            return start.plus(granularity.length());
        }

    }

    /**
     * Parses a {@code from} or {@code until} argument.
     *
     * @param name  the argument name, for messages
     * @param value the value
     * @return the datestamp
     * @throws OaiException {@link OaiError#BAD_ARGUMENT} when the value is not a legal datestamp
     */
    public static Datestamp parse(final String name, final String value) {
        try {
            if (DAY.matcher(value).matches()) {
                return new Datestamp(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant(), Granularity.DAY);
            }
            if (SECOND.matcher(value).matches()) {
                return new Datestamp(Instant.parse(value), Granularity.SECOND);
            }
        } catch (DateTimeParseException e) {
            // an impossible date such as 2026-02-30: reported below
        }
        throw new OaiException(OaiError.BAD_ARGUMENT, "The " + name + " argument is not a UTC datestamp of the form "
            + "YYYY-MM-DD or " + GRANULARITY + ": " + value);
    }

    /**
     * Checks a selective harvesting range.
     *
     * @param from  the lower bound, may be {@code null}
     * @param until the upper bound, may be {@code null}
     * @throws OaiException {@link OaiError#BAD_ARGUMENT} when the bounds have different
     *                      granularities or from is after until
     */
    public static void checkRange(final Datestamp from, final Datestamp until) {
        if (from == null || until == null) {
            return;
        }
        if (from.granularity() != until.granularity()) {
            throw new OaiException(OaiError.BAD_ARGUMENT, "The from and until arguments have different granularities");
        }
        if (from.start().isAfter(until.start())) {
            throw new OaiException(OaiError.BAD_ARGUMENT, "The from argument is later than the until argument");
        }
    }

    /**
     * Formats an instant at the repository's granularity (truncating fractions of seconds).
     *
     * @param instant the instant
     * @return for example {@code 2026-11-05T18:30:00Z}
     */
    public static String format(final Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

}
