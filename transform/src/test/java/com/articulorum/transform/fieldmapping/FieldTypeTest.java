package com.articulorum.transform.fieldmapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link FieldType}.
 */
class FieldTypeTest {

    private final Model model = ModelFactory.createDefaultModel();

    /** Type names are case and separator insensitive, with xsd: aliases. */
    @ParameterizedTest
    @CsvSource({"string,STRING", "xsd:anyURI,URI", "iri,URI", "dateTime,DATE_TIME", "date-time,DATE_TIME",
        "xsd:date,DATE_TIME", "long,INTEGER", "double,DECIMAL", "Boolean,BOOLEAN"})
    void parsesTypeNames(final String name, final FieldType expected) {
        assertThat(FieldType.parse(name)).isEqualTo(expected);
    }

    /** No type means string; an unknown type is an error. */
    @Test
    void defaultsAndRejectsUnknownTypes() {
        assertThat(FieldType.parse(null)).isEqualTo(FieldType.STRING);
        assertThat(FieldType.parse(" ")).isEqualTo(FieldType.STRING);
        assertThatIllegalArgumentException().isThrownBy(() -> FieldType.parse("geo"));
    }

    /** Strings and URIs keep the lexical form or IRI. */
    @Test
    void convertsStringsAndUris() {
        assertThat(FieldType.STRING.convert(model.createLiteral("Moby Dick", "en"))).hasValue("Moby Dick");
        assertThat(FieldType.URI.convert(model.createResource("http://example.org/a"))).hasValue("http://example.org/a");
    }

    /** Dates of several shapes are normalised to UTC instants. */
    @Test
    void normalisesDates() {
        assertThat(FieldType.DATE_TIME.convert(model.createLiteral("2026-10-30T12:00:00Z")))
            .hasValue("2026-10-30T12:00:00Z");
        assertThat(FieldType.DATE_TIME.convert(model.createLiteral("2026-10-30T12:00:00+02:00")))
            .hasValue("2026-10-30T10:00:00Z");
        assertThat(FieldType.DATE_TIME.convert(model.createLiteral("2026-10-30"))).hasValue("2026-10-30T00:00:00Z");
    }

    /** Numbers and booleans are converted; unparsable values are skipped rather than failing. */
    @Test
    void convertsNumbersAndBooleans() {
        assertThat(FieldType.INTEGER.convert(model.createLiteral(" 42 "))).hasValue(42L);
        assertThat(FieldType.DECIMAL.convert(model.createLiteral("1.50"))).hasValue(new BigDecimal("1.50"));
        assertThat(FieldType.BOOLEAN.convert(model.createTypedLiteral("true", XSDDatatype.XSDboolean)))
            .hasValue(true);
        assertThat(FieldType.BOOLEAN.convert(model.createLiteral("0"))).hasValue(false);
        assertThat(FieldType.BOOLEAN.convert(model.createLiteral("maybe"))).isEmpty();
        assertThat(FieldType.INTEGER.convert(model.createLiteral("forty"))).isEmpty();
        assertThat(FieldType.DATE_TIME.convert(model.createLiteral("yesterday"))).isEmpty();
    }

    /** Blank nodes and missing nodes produce nothing. */
    @Test
    void skipsBlankNodes() {
        assertThat(FieldType.STRING.convert(model.createResource())).isEmpty();
        assertThat(FieldType.STRING.convert(null)).isEmpty();
    }

}
