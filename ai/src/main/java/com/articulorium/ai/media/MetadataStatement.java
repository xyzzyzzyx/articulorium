package com.articulorium.ai.media;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A suggested statement about a resource: {@code <resource> <predicate> value}. The caller reviews
 * it and applies the ones it keeps with a SPARQL Update of the resource (its description, for a
 * binary).
 *
 * @param predicate the predicate IRI
 * @param value     the value: a literal's lexical form, or an IRI
 * @param kind      whether the value is a literal or an IRI
 * @param language  the literal's language tag, may be {@code null}
 * @param datatype  the literal's datatype IRI, {@code null} for plain strings
 * @param origin    where it comes from: read from the file, or suggested by the model
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetadataStatement(String predicate, String value, Kind kind, String language, String datatype,
        Origin origin) {

    /**
     * A literal statement.
     *
     * @param predicate the predicate
     * @param value     the lexical form
     * @param language  the language tag, may be {@code null}
     * @param datatype  the datatype, may be {@code null}
     * @param origin    the origin
     * @return the statement
     */
    public static MetadataStatement literal(final String predicate, final String value, final String language,
            final String datatype, final Origin origin) {
        return new MetadataStatement(predicate, value, Kind.LITERAL, language, datatype, origin);
    }

    /**
     * Tells whether another statement says the same (predicate and value, whatever the origin).
     *
     * @param other the other statement
     * @return {@code true} when it does
     */
    public boolean sameAs(final MetadataStatement other) {
        return predicate.equals(other.predicate) && kind == other.kind
            && value.strip().equalsIgnoreCase(other.value.strip()) && Objects.equals(datatype, other.datatype);
    }

    /** Literal or IRI. */
    public enum Kind {

        /** A literal. */
        LITERAL,

        /** An IRI. */
        IRI

    }

    /** Where a statement comes from. */
    public enum Origin {

        /** Read from the file (EXIF, IPTC, container or document information). */
        FILE,

        /** Suggested by the model. */
        MODEL

    }

}
