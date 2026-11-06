package com.articulorium.oai.metadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorium.oai.config.OaiProperties;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Maps the RDF description of a resource to unqualified Dublin Core ({@code oai_dc}).
 *
 * <p>Each of the fifteen elements carries the values of the RDF properties configured for it
 * ({@code articulorium.oai.dublin-core.<element>}): literals as their lexical form with their
 * language tag, IRIs as written, blank nodes not at all. Values of one property are sorted, so the
 * output does not depend on the order the platform serialised the triples in. Every record also
 * identifies its landing page in the user interface and its LDP resource.</p>
 */
@Component
public class DublinCoreMapper {

    /** The elements of unqualified Dublin Core, in the order they are written. */
    public static final List<String> ELEMENTS = List.of("title", "creator", "subject", "description", "publisher",
        "contributor", "date", "type", "format", "identifier", "source", "language", "relation", "coverage", "rights");

    private static final String IDENTIFIER = "identifier";

    private final Map<String, List<String>> properties;

    /**
     * Creates the mapper.
     *
     * @param settings OAI-PMH settings
     * @throws IllegalStateException when the mapping names something other than a Dublin Core
     *                               element
     */
    public DublinCoreMapper(final OaiProperties settings) {
        final Map<String, List<String>> configured = settings.getDublinCore();
        for (final String element : configured.keySet()) {
            if (!ELEMENTS.contains(element)) {
                throw new IllegalStateException("articulorium.oai.dublin-core." + element
                    + " is not an unqualified Dublin Core element; use one of " + ELEMENTS);
            }
        }
        final Map<String, List<String>> ordered = new LinkedHashMap<>();
        ELEMENTS.forEach(element -> ordered.put(element, List.copyOf(configured.getOrDefault(element, List.of()))));
        this.properties = ordered;
    }

    /**
     * Maps a resource.
     *
     * @param model       its RDF description
     * @param resourceIri its IRI (the subject of its description)
     * @param landingPage its page in the user interface, may be {@code null}
     * @return the Dublin Core values, element by element
     */
    public List<DcValue> map(final Model model, final String resourceIri, final String landingPage) {
        final List<Resource> subjects = subjects(model, resourceIri);
        final List<DcValue> values = new ArrayList<>();
        for (final Map.Entry<String, List<String>> element : properties.entrySet()) {
            final Set<DcValue> elementValues = new LinkedHashSet<>();
            if (IDENTIFIER.equals(element.getKey())) {
                if (StringUtils.hasText(landingPage)) {
                    elementValues.add(new DcValue(IDENTIFIER, landingPage, null));
                }
                elementValues.add(new DcValue(IDENTIFIER, resourceIri, null));
            }
            for (final String property : element.getValue()) {
                elementValues.addAll(valuesOf(model, subjects, property, element.getKey()));
            }
            values.addAll(elementValues);
        }
        return values;
    }

    private static List<Resource> subjects(final Model model, final String iri) {
        // containers may be described with or without a trailing slash
        final List<Resource> subjects = new ArrayList<>();
        subjects.add(model.createResource(iri));
        subjects.add(model.createResource(iri.endsWith("/") ? iri.substring(0, iri.length() - 1) : iri + "/"));
        return subjects;
    }

    private static List<DcValue> valuesOf(final Model model, final List<Resource> subjects, final String property,
            final String element) {
        final List<DcValue> values = new ArrayList<>();
        for (final Resource subject : subjects) {
            final List<Statement> statements = model.listStatements(subject, model.createProperty(property),
                (RDFNode) null).toList();
            for (final Statement statement : statements) {
                final RDFNode object = statement.getObject();
                if (object.isLiteral()) {
                    final Literal literal = object.asLiteral();
                    final String language = literal.getLanguage();
                    if (StringUtils.hasText(literal.getLexicalForm())) {
                        values.add(new DcValue(element, literal.getLexicalForm(),
                            StringUtils.hasText(language) ? language : null));
                    }
                } else if (object.isURIResource()) {
                    values.add(new DcValue(element, object.asResource().getURI(), null));
                }
            }
        }
        values.sort(Comparator.comparing(DcValue::value)
            .thenComparing(value -> value.language() == null ? "" : value.language()));
        return values;
    }

}
