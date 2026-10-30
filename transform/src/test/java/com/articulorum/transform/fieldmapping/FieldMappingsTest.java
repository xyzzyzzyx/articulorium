package com.articulorum.transform.fieldmapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FieldMappings}.
 */
class FieldMappingsTest {

    private static FieldMappings load(final String yaml) throws IOException {
        return FieldMappings.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Short and long forms, alternatives, multi-step paths and full IRIs are understood. */
    @Test
    void loadsFieldDefinitions() throws IOException {
        final FieldMappings mappings = load("""
            prefixes:
              ex: "http://example.org/"
            fields:
              title: "dcterms:title | dc:title"
              created:
                paths: [ "ex:created" ]
                type: dateTime
              author:
                paths: "ex:author / <http://xmlns.com/foaf/0.1/name>"
            """);

        assertThat(mappings.prefixes()).containsEntry("ex", "http://example.org/").containsKey("dcterms");
        assertThat(mappings.fields()).extracting(FieldMapping::name).containsExactly("title", "created", "author");
        assertThat(mappings.fields().get(0).paths()).containsExactly(
            List.of("http://purl.org/dc/terms/title"), List.of("http://purl.org/dc/elements/1.1/title"));
        assertThat(mappings.fields().get(0).type()).isEqualTo(FieldType.STRING);
        assertThat(mappings.fields().get(1).type()).isEqualTo(FieldType.DATE_TIME);
        assertThat(mappings.fields().get(2).paths()).containsExactly(
            List.of("http://example.org/author", "http://xmlns.com/foaf/0.1/name"));
    }

    /** Errors in the document are reported as I/O failures naming the problem. */
    @Test
    void rejectsInvalidDocuments() {
        assertThatThrownBy(() -> load("- just a list")).isInstanceOf(IOException.class)
            .hasMessageContaining("must be a map");
        assertThatThrownBy(() -> load("prefixes: {}")).isInstanceOf(IOException.class)
            .hasMessageContaining("'fields'");
        assertThatThrownBy(() -> load("fields: { a: 'nope:x' }")).isInstanceOf(IOException.class)
            .hasMessageContaining("unknown prefix");
        assertThatThrownBy(() -> load("fields: { a: 'plain' }")).isInstanceOf(IOException.class)
            .hasMessageContaining("not a prefixed name");
        assertThatThrownBy(() -> load("fields: { a: { paths: [ 'dc:title' ], type: geo } }"))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> load("fields: { a: 5 }")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> load("fields: [ : ")).isInstanceOf(IOException.class);
    }

    /** Prefixed names expand; angle brackets are stripped. */
    @Test
    void expandsTerms() {
        final Map<String, String> prefixes = Map.of("ex", "http://example.org/");

        assertThat(FieldMappings.expand("ex:a", prefixes, "f")).isEqualTo("http://example.org/a");
        assertThat(FieldMappings.expand("<urn:x>", prefixes, "f")).isEqualTo("urn:x");
    }

    /** The bundled definitions (ported from the former LDPath program) load. */
    @Test
    void bundledMappingsLoad() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/transform/field-mappings.yml")) {
            final FieldMappings mappings = FieldMappings.load(in);

            assertThat(mappings.fields()).extracting(FieldMapping::name).contains("created", "lastModified");
        }
    }

}
