package com.articulorum.transform.fieldmapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.apache.jena.rdf.model.RDFNode;

/**
 * Value types of document fields, and the conversion of RDF terms into them. A term that cannot
 * be converted (for example a non-numeric literal for an integer field) is skipped.
 */
public enum FieldType {

    /** The lexical form of a literal, or the IRI of a resource. */
    STRING,

    /** The IRI of a resource (literals contribute their lexical form). */
    URI,

    /** An ISO-8601 instant in UTC; dates without time mean midnight UTC. */
    DATE_TIME,

    /** A whole number. */
    INTEGER,

    /** A decimal number. */
    DECIMAL,

    /** {@code true} or {@code false}. */
    BOOLEAN;

    /**
     * Converts an RDF term into a field value.
     *
     * @param node the term
     * @return the value, or empty when the term is a blank node or cannot be converted
     */
    public Optional<Object> convert(final RDFNode node) {
        if (node == null || node.isAnon()) {
            return Optional.empty();
        }
        final String lexical = node.isLiteral() ? node.asLiteral().getLexicalForm() : node.asResource().getURI();
        if (lexical == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(switch (this) {
                case STRING, URI -> lexical;
                case DATE_TIME -> dateTime(lexical.trim());
                case INTEGER -> Long.valueOf(lexical.trim());
                case DECIMAL -> new BigDecimal(lexical.trim());
                case BOOLEAN -> bool(node, lexical.trim());
            });
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String dateTime(final String lexical) {
        try {
            return Instant.parse(lexical).toString();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(lexical).toInstant().toString();
            } catch (DateTimeParseException ignored) {
                return LocalDate.parse(lexical).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            }
        }
    }

    private static Boolean bool(final RDFNode node, final String lexical) {
        if (node.isLiteral() && node.asLiteral().getValue() instanceof Boolean value) {
            return value;
        }
        if ("true".equalsIgnoreCase(lexical) || "1".equals(lexical)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(lexical) || "0".equals(lexical)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Parses a type name as written in field mapping files ({@code string}, {@code uri},
     * {@code dateTime}, {@code integer}, {@code decimal}, {@code boolean}); XML Schema style
     * names such as {@code xsd:anyURI} are accepted too.
     *
     * @param name the type name, {@code null} for {@link #STRING}
     * @return the type
     * @throws IllegalArgumentException for unknown names
     */
    public static FieldType parse(final String name) {
        if (name == null || name.isBlank()) {
            return STRING;
        }
        final String normalized = name.trim().replaceFirst("^xsd:", "").replace("_", "").replace("-", "")
            .toLowerCase();
        return switch (normalized) {
            case "string" -> STRING;
            case "uri", "anyuri", "iri" -> URI;
            case "datetime", "date" -> DATE_TIME;
            case "integer", "int", "long" -> INTEGER;
            case "decimal", "double", "float" -> DECIMAL;
            case "boolean" -> BOOLEAN;
            default -> throw new IllegalArgumentException("Unknown field type: " + name);
        };
    }

}
