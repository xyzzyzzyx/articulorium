package com.articulorum.transform.fieldmapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A set of {@link FieldMapping}s read from a YAML document, the declarative replacement of an
 * LDPath program.
 *
 * <pre>
 * prefixes:
 *   dcterms: http://purl.org/dc/terms/
 *   dc: http://purl.org/dc/elements/1.1/
 * fields:
 *   title:
 *     paths: [ "dc:title", "dcterms:title" ]   # alternatives: values are combined
 *     type: string                            # string, uri, dateTime, integer, decimal, boolean
 *   memberTitle:
 *     paths: [ "pcdm:hasMember / dcterms:title" ]   # a path of several steps
 * </pre>
 *
 * <p>A field may also be written in short form, {@code title: "dc:title | dcterms:title"}, which
 * means type {@code string}. Predicates are prefixed names or absolute IRIs in angle brackets
 * ({@code <http://example.org/p>}).</p>
 *
 * @param prefixes the prefix declarations
 * @param fields   the field definitions, in document order
 */
public record FieldMappings(Map<String, String> prefixes, List<FieldMapping> fields) {

    /** Prefix declarations every document may use without declaring them. */
    public static final Map<String, String> BUILT_IN_PREFIXES = Map.of(
        "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
        "owl", "http://www.w3.org/2002/07/owl#",
        "xsd", "http://www.w3.org/2001/XMLSchema#",
        "skos", "http://www.w3.org/2004/02/skos/core#",
        "dc", "http://purl.org/dc/elements/1.1/",
        "dcterms", "http://purl.org/dc/terms/");

    /**
     * Creates the mappings.
     *
     * @param prefixes the prefix declarations
     * @param fields   the field definitions
     */
    public FieldMappings {
        prefixes = Map.copyOf(prefixes);
        fields = List.copyOf(fields);
    }

    /**
     * Reads mappings from a YAML document.
     *
     * @param yaml the document; it is not closed
     * @return the mappings
     * @throws IOException when the document cannot be read or is invalid
     */
    public static FieldMappings load(final InputStream yaml) throws IOException {
        final Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new IOException("Invalid field mapping document: " + e.getMessage(), e);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new IOException("A field mapping document must be a map with 'prefixes' and 'fields'");
        }
        final Map<String, String> prefixes = new LinkedHashMap<>(BUILT_IN_PREFIXES);
        if (root.get("prefixes") instanceof Map<?, ?> declared) {
            declared.forEach((prefix, namespace) -> prefixes.put(String.valueOf(prefix), String.valueOf(namespace)));
        }
        if (!(root.get("fields") instanceof Map<?, ?> fields)) {
            throw new IOException("A field mapping document needs a 'fields' map");
        }
        final List<FieldMapping> mappings = new ArrayList<>();
        try {
            for (final Map.Entry<?, ?> entry : fields.entrySet()) {
                mappings.add(field(String.valueOf(entry.getKey()), entry.getValue(), prefixes));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        return new FieldMappings(prefixes, mappings);
    }

    private static FieldMapping field(final String name, final Object definition, final Map<String, String> prefixes) {
        final List<String> expressions = new ArrayList<>();
        String type = null;
        if (definition instanceof String shortForm) {
            for (final String alternative : shortForm.split("\\|")) {
                expressions.add(alternative);
            }
        } else if (definition instanceof Map<?, ?> map) {
            final Object paths = map.get("paths");
            if (paths instanceof List<?> list) {
                list.forEach(path -> expressions.add(String.valueOf(path)));
            } else if (paths != null) {
                expressions.add(String.valueOf(paths));
            }
            type = map.get("type") == null ? null : String.valueOf(map.get("type"));
        } else {
            throw new IllegalArgumentException("Field " + name + " must be a path string or a map");
        }
        final List<List<String>> paths = new ArrayList<>();
        for (final String expression : expressions) {
            final List<String> steps = new ArrayList<>();
            for (final String step : expression.split("/(?![^<]*>)")) {
                if (!step.isBlank()) {
                    steps.add(expand(step.trim(), prefixes, name));
                }
            }
            paths.add(steps);
        }
        return new FieldMapping(name, paths, FieldType.parse(type));
    }

    /**
     * Expands a predicate written as a prefixed name or an IRI in angle brackets.
     *
     * @param term     the predicate
     * @param prefixes the known prefixes
     * @param field    the field being read, for error messages
     * @return the absolute IRI
     * @throws IllegalArgumentException when the prefix is unknown
     */
    static String expand(final String term, final Map<String, String> prefixes, final String field) {
        if (term.startsWith("<") && term.endsWith(">")) {
            return term.substring(1, term.length() - 1);
        }
        final int colon = term.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Field " + field + ": '" + term + "' is not a prefixed name or <IRI>");
        }
        final String namespace = prefixes.get(term.substring(0, colon));
        if (namespace == null) {
            throw new IllegalArgumentException("Field " + field + ": unknown prefix in '" + term + "'");
        }
        return namespace + term.substring(colon + 1);
    }

}
