package com.articulorium.oai.metadata;

/**
 * One unqualified Dublin Core element of a record.
 *
 * @param element  the element name, for example {@code title}
 * @param value    its value
 * @param language the language tag of the value, may be {@code null}
 */
public record DcValue(String element, String value, String language) {
}
