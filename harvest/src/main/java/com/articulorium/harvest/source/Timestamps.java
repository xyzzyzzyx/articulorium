package com.articulorium.harvest.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import tools.jackson.databind.JsonNode;

/**
 * Reads the time stamps repositories answer: ISO-8601 instants with or without offset or fraction,
 * {@code +0000}-style offsets, plain dates, or seconds since the epoch.
 */
public final class Timestamps {

    private static final DateTimeFormatter COMPACT_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z");

    private Timestamps() {
        // utility class
    }

    /**
     * Parses a time stamp.
     *
     * @param text the text, may be {@code null}
     * @return the instant, or {@code null} when absent or unreadable
     */
    public static Instant parse(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String value = text.trim();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            // try the other shapes
        }
        try {
            return OffsetDateTime.parse(value, COMPACT_OFFSET).toInstant();
        } catch (DateTimeParseException e) {
            // try the other shapes
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            // try the other shapes
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Reads a JSON time stamp: a string as {@link #parse(String)}, or a number of seconds since the
     * epoch (with a fraction).
     *
     * @param node the value
     * @return the instant, or {@code null}
     */
    public static Instant parse(final JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            final BigDecimal seconds = node.decimalValue();
            return Instant.ofEpochSecond(seconds.longValue(),
                seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return node.isString() ? parse(node.asString()) : null;
    }

}
