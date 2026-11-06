package com.articulorium.harvest.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * The shared ingest representation of a harvested record, whatever its source.
 *
 * <p>Metadata is RDF: sources that speak RDF (Fedora, VIVO) pass their graph through, the others
 * map their metadata to RDF (Dublin Core to {@code dcterms:}). The graph describes
 * {@link #subject()}, the IRI that stands for the record itself in the source; ingest renames it to
 * the IRI of the ingested copy and keeps other subjects (VIVO positions, hash IRIs) as they are.</p>
 *
 * @param identifier the record's identifier in its source, stable across harvests (an OAI
 *                   identifier, a DSpace UUID, a Fedora path, a VIVO individual's IRI)
 * @param subject    the absolute IRI that stands for the record in {@link #metadata()}
 * @param datestamp  when the source last changed the record, may be {@code null}
 * @param deleted    whether the source reports the record as deleted (metadata is then empty)
 * @param sets       the sets (collections, communities, OAI sets) the source puts the record in
 * @param metadata   the metadata
 * @param files      the record's files, referenced where the source keeps them
 */
public record HarvestedRecord(String identifier, String subject, Instant datestamp, boolean deleted,
        List<String> sets, Model metadata, List<HarvestedFile> files) {

    /**
     * Creates a record.
     *
     * @param identifier the identifier (not blank)
     * @param subject    the subject IRI (absolute)
     * @param datestamp  the datestamp
     * @param deleted    whether the record was deleted
     * @param sets       the sets
     * @param metadata   the metadata
     * @param files      the files
     */
    public HarvestedRecord {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("A harvested record needs an identifier");
        }
        Objects.requireNonNull(subject, "subject");
        if (!isAbsoluteIri(subject)) {
            throw new IllegalArgumentException("The subject of a harvested record must be an absolute IRI: " + subject);
        }
        sets = List.copyOf(sets);
        files = List.copyOf(files);
        metadata = metadata == null ? ModelFactory.createDefaultModel() : metadata;
    }

    /**
     * A record that exists in the source.
     *
     * @param identifier the identifier
     * @param subject    the subject IRI
     * @param datestamp  the datestamp, may be {@code null}
     * @param metadata   the metadata
     * @return the record
     */
    public static HarvestedRecord of(final String identifier, final String subject, final Instant datestamp,
            final Model metadata) {
        return new HarvestedRecord(identifier, subject, datestamp, false, List.of(), metadata, List.of());
    }

    /**
     * A record the source reports as deleted.
     *
     * @param identifier the identifier
     * @param subject    the subject IRI
     * @param datestamp  when it was deleted, may be {@code null}
     * @return the record
     */
    public static HarvestedRecord deleted(final String identifier, final String subject, final Instant datestamp) {
        return new HarvestedRecord(identifier, subject, datestamp, true, List.of(), null, List.of());
    }

    /**
     * Returns a copy in other sets.
     *
     * @param newSets the sets
     * @return the copy
     */
    public HarvestedRecord withSets(final List<String> newSets) {
        return new HarvestedRecord(identifier, subject, datestamp, deleted, newSets, metadata, files);
    }

    /**
     * Returns a copy with other files.
     *
     * @param newFiles the files
     * @return the copy
     */
    public HarvestedRecord withFiles(final List<HarvestedFile> newFiles) {
        return new HarvestedRecord(identifier, subject, datestamp, deleted, sets, metadata, newFiles);
    }

    /**
     * Tells whether a string is an absolute IRI that can appear in RDF (no spaces, angle brackets or
     * other characters N-Triples forbids in IRIs).
     *
     * @param iri the string
     * @return {@code true} for an absolute IRI
     */
    public static boolean isAbsoluteIri(final String iri) {
        if (iri == null || iri.isEmpty()) {
            return false;
        }
        for (int i = 0; i < iri.length(); i++) {
            final char c = iri.charAt(i);
            if (c <= ' ' || "<>\"{}|^`\\".indexOf(c) >= 0) {
                return false;
            }
        }
        try {
            return new URI(iri).isAbsolute();
        } catch (URISyntaxException e) {
            // IRIs may hold non-ASCII characters that java.net.URI accepts; anything else is not an IRI
            return false;
        }
    }

}
