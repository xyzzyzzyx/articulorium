package com.articulorium.oai.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * The state of an incomplete list (section 3.5), handed to harvesters as an opaque token.
 *
 * <p>A token repeats the original arguments of the request and records where the list continues:
 * lists are ordered by modification time and then by path, and continue at {@code offset} results
 * past the {@code anchor} time (the time of the last resource the previous response covered).
 * Continuing after a time rather than after a number of results means that resources added,
 * changed or deleted during a harvest neither shift the rest of the list nor make it skip
 * records. Tokens hold no state on the server, so they never expire.</p>
 *
 * @param verb           the verb the token continues
 * @param metadataPrefix the metadata format (list verbs other than ListSets)
 * @param set            the set argument, may be {@code null}
 * @param from           the from argument, may be {@code null}
 * @param until          the until argument, may be {@code null}
 * @param anchor         the modification time the list continues at, {@code null} at the start
 * @param offset         the number of results at or after the anchor (from the start for
 *                       ListSets) already covered
 * @param cursor         the number of items answered before the continuation, as
 *                       the {@code cursor} attribute counts them
 */
public record ResumptionToken(OaiVerb verb, String metadataPrefix, String set, String from, String until,
        Instant anchor, int offset, int cursor) {

    private static final String VERSION = "1";

    private static final String SEPARATOR = "|";

    private static final int FIELDS = 9;

    /**
     * Writes the token.
     *
     * @return the opaque token: unpadded URL-safe Base64, so it needs no escaping in URLs
     */
    public String encode() {
        final String plain = String.join(SEPARATOR, VERSION, verb.value(), nullToEmpty(metadataPrefix),
            nullToEmpty(set), nullToEmpty(from), nullToEmpty(until), anchor == null ? "" : anchor.toString(),
            Integer.toString(offset), Integer.toString(cursor));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a token a harvester sent back.
     *
     * @param token the token
     * @param verb  the verb of the request carrying it
     * @return the state
     * @throws OaiException {@link OaiError#BAD_RESUMPTION_TOKEN} when the token was not issued by
     *                      this repository for the verb
     */
    public static ResumptionToken decode(final String token, final OaiVerb verb) {
        try {
            final String plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            final String[] fields = plain.split("\\" + SEPARATOR, -1);
            if (fields.length != FIELDS || !VERSION.equals(fields[0]) || !verb.value().equals(fields[1])) {
                throw invalid(token);
            }
            final int offset = Integer.parseInt(fields[7]);
            final int cursor = Integer.parseInt(fields[8]);
            if (offset < 0 || cursor < 0) {
                throw invalid(token);
            }
            return new ResumptionToken(verb, emptyToNull(fields[2]), emptyToNull(fields[3]), emptyToNull(fields[4]),
                emptyToNull(fields[5]), fields[6].isEmpty() ? null : Instant.parse(fields[6]), offset, cursor);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid(token);
        }
    }

    private static OaiException invalid(final String token) {
        return new OaiException(OaiError.BAD_RESUMPTION_TOKEN, "Invalid resumption token: " + token);
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(final String value) {
        return value.isEmpty() ? null : value;
    }

}
