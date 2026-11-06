package com.articulorium.harvest.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

import com.articulorium.domain.rdf.ResourcePaths;

/**
 * Where harvested records are stored: {@code <container>/<source>/<slug>}, with a slug derived from
 * the record's identifier alone, so harvesting a record again replaces its copy.
 */
public final class RecordPaths {

    /** Longest readable part of a slug (its end, the most specific part of most identifiers). */
    private static final int READABLE = 60;

    /** Hexadecimal digits of the identifier's SHA-256 kept in a slug. */
    private static final int HASH = 12;

    private final String container;

    /**
     * Creates the paths.
     *
     * @param container the container holding a container per source
     */
    public RecordPaths(final String container) {
        this.container = ResourcePaths.normalize(container);
        if (this.container.isEmpty()) {
            throw new IllegalArgumentException("The harvest container must not be the LDP root");
        }
    }

    /**
     * Returns the container holding every source's container.
     *
     * @return the path
     */
    public String root() {
        return container;
    }

    /**
     * Returns the container of a source.
     *
     * @param sourceId the source identifier
     * @return the path
     */
    public String source(final String sourceId) {
        return container + "/" + sourceId;
    }

    /**
     * Returns the path of a record.
     *
     * @param sourceId   the source identifier
     * @param identifier the record identifier
     * @return the path
     */
    public String record(final String sourceId, final String identifier) {
        return source(sourceId) + "/" + slug(identifier);
    }

    /**
     * Derives the slug of a record: the end of its identifier in lower-case letters, digits and
     * hyphens, then 12 hexadecimal digits of the identifier's SHA-256 (identifiers that read alike
     * still get different slugs). {@code oai:arXiv.org:2401.00001} becomes
     * {@code oai-arxiv-org-2401-00001-1b2c...}.
     *
     * @param identifier the identifier
     * @return the slug
     */
    public static String slug(final String identifier) {
        String readable = Normalizer.normalize(identifier, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (readable.length() > READABLE) {
            readable = readable.substring(readable.length() - READABLE).replaceAll("^-+", "");
        }
        final String hash = sha256(identifier).substring(0, HASH);
        return readable.isEmpty() ? hash : readable + "-" + hash;
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}
