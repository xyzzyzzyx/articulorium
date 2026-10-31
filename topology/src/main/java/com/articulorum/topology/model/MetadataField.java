package com.articulorum.topology.model;

/**
 * A metadata field of a topology node: a predicate the user interface offers when describing a
 * resource of that node, optionally with a default value written on creation.
 *
 * @param predicate    the predicate IRI, for example {@code http://purl.org/dc/terms/creator}
 * @param label        the field's label
 * @param required     whether a value is expected
 * @param repeatable   whether several values are allowed
 * @param defaultValue a literal written when the resource is built, may be {@code null}
 * @param datatype     the literal's datatype IRI, {@code null} for plain strings
 */
public record MetadataField(String predicate, String label, boolean required, boolean repeatable,
        String defaultValue, String datatype) {
}
