package com.articulorium.ai.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

/**
 * Reads the descriptive texts of a resource from its RDF: what the agent shows the model about
 * repository resources.
 */
public final class Descriptions {

    /** Dublin Core terms. */
    public static final String DCTERMS = "http://purl.org/dc/terms/";

    /** Dublin Core elements. */
    public static final String DC = "http://purl.org/dc/elements/1.1/";

    static final List<String> TITLES = List.of(DCTERMS + "title", DC + "title", Rdf.RDFS_LABEL,
        "http://www.w3.org/2004/02/skos/core#prefLabel", "http://xmlns.com/foaf/0.1/name");

    static final List<String> DESCRIPTIONS = List.of(DCTERMS + "description", DCTERMS + "abstract", DC + "description");

    static final List<String> SUBJECTS = List.of(DCTERMS + "subject", DC + "subject",
        "http://vivoweb.org/ontology/core#freetextKeyword", "http://schema.org/keywords");

    static final List<String> CREATORS = List.of(DCTERMS + "creator", DC + "creator", DCTERMS + "contributor",
        DC + "contributor");

    static final List<String> DATES = List.of(DCTERMS + "date", DCTERMS + "issued", DCTERMS + "created", DC + "date");

    static final List<String> TYPES = List.of(DCTERMS + "type", DC + "type");

    private Descriptions() {
    }

    /**
     * Returns the first title of a resource.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the title, or empty
     */
    public static String title(final Model model, final String subject) {
        return texts(model, subject, TITLES).stream().findFirst().orElse("");
    }

    /**
     * Returns the descriptions and abstracts of a resource.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the texts
     */
    public static List<String> descriptions(final Model model, final String subject) {
        return texts(model, subject, DESCRIPTIONS);
    }

    /**
     * Returns the subjects and keywords of a resource (literals, and labels of linked concepts).
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the subjects
     */
    public static List<String> subjects(final Model model, final String subject) {
        return texts(model, subject, SUBJECTS);
    }

    /**
     * Returns the creators and contributors of a resource.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return their names
     */
    public static List<String> creators(final Model model, final String subject) {
        return texts(model, subject, CREATORS);
    }

    /**
     * Returns the dates of a resource.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the dates as written
     */
    public static List<String> dates(final Model model, final String subject) {
        return texts(model, subject, DATES);
    }

    /**
     * Returns the Dublin Core types of a resource (genre: thesis, article, photograph ...).
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the types
     */
    public static List<String> genres(final Model model, final String subject) {
        return texts(model, subject, TYPES);
    }

    /**
     * Returns the {@code rdf:type}s of a resource, without the platform's LDP types.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the type IRIs
     */
    public static List<String> types(final Model model, final String subject) {
        final List<String> types = new ArrayList<>();
        model.listObjectsOfProperty(model.createResource(subject), model.createProperty(Rdf.TYPE))
            .forEachRemaining(node -> {
                if (node.isURIResource() && !node.asResource().getURI().startsWith(Ldp.NAMESPACE)
                        && !node.asResource().getURI().startsWith("http://fedora.info/definitions/")) {
                    types.add(node.asResource().getURI());
                }
            });
        return types;
    }

    /**
     * Tells whether a resource has a type.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @param type    the type IRI
     * @return {@code true} when it has
     */
    public static boolean hasType(final Model model, final String subject, final String type) {
        return model.contains(model.createResource(subject), model.createProperty(Rdf.TYPE), model.createResource(type));
    }

    /**
     * Returns the IRIs a container contains.
     *
     * @param model   its RDF
     * @param subject its IRI
     * @return the children's IRIs
     */
    public static List<String> children(final Model model, final String subject) {
        final List<String> children = new ArrayList<>();
        model.listObjectsOfProperty(model.createResource(subject), model.createProperty(Ldp.CONTAINS))
            .forEachRemaining(node -> {
                if (node.isURIResource()) {
                    children.add(node.asResource().getURI());
                }
            });
        children.sort(String::compareTo);
        return children;
    }

    /**
     * Returns the literal values of a property.
     *
     * @param model     the RDF
     * @param subject   the subject IRI
     * @param predicate the property IRI
     * @return the lexical forms
     */
    public static List<String> literals(final Model model, final String subject, final String predicate) {
        final List<String> values = new ArrayList<>();
        model.listObjectsOfProperty(model.createResource(subject), model.createProperty(predicate))
            .forEachRemaining(node -> {
                if (node.isLiteral()) {
                    values.add(node.asLiteral().getLexicalForm());
                }
            });
        return values;
    }

    private static List<String> texts(final Model model, final String subject, final List<String> predicates) {
        final Set<String> values = new LinkedHashSet<>();
        final Resource resource = model.createResource(subject);
        for (final String predicate : predicates) {
            model.listObjectsOfProperty(resource, model.createProperty(predicate)).forEachRemaining(node -> {
                final String text = text(model, node);
                if (!text.isBlank()) {
                    values.add(text.strip());
                }
            });
        }
        return new ArrayList<>(values);
    }

    private static String text(final Model model, final RDFNode node) {
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        if (node.isResource()) {
            // a linked concept or agent described in the same graph (VIVO, SKOS)
            for (final String label : TITLES) {
                final var labels = model.listObjectsOfProperty(node.asResource(), model.createProperty(label)).toList();
                for (final RDFNode value : labels) {
                    if (value.isLiteral()) {
                        return value.asLiteral().getLexicalForm();
                    }
                }
            }
        }
        return "";
    }

}
