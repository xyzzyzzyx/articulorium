package com.articulorum.platform.rdf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.vocabulary.Rdf;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * Converts between Apache Jena graphs and the platform's persisted {@link Triple}s.
 *
 * <p>IRIs below the server base are stored relative (see {@link Iris}); blank nodes keep their
 * labels so a rendered and re-parsed graph maps back onto the same triples.</p>
 */
public final class TripleMapper {

    private TripleMapper() {
        // utility class
    }

    /**
     * Converts every statement of a model to a triple.
     *
     * @param model the model
     * @param base  the server base used to relativise IRIs
     * @return the triples, in model iteration order
     */
    public static List<Triple> toTriples(final Model model, final String base) {
        final List<Triple> triples = new ArrayList<>();
        final ExtendedIterator<org.apache.jena.graph.Triple> iterator =
            model.getGraph().find(Node.ANY, Node.ANY, Node.ANY);
        try {
            while (iterator.hasNext()) {
                triples.add(toTriple(iterator.next(), base));
            }
        } finally {
            iterator.close();
        }
        return triples;
    }

    /**
     * Converts one Jena triple.
     *
     * @param triple the Jena triple
     * @param base   the server base used to relativise IRIs
     * @return the platform triple
     */
    public static Triple toTriple(final org.apache.jena.graph.Triple triple, final String base) {
        final Node subject = triple.getSubject();
        final String subjectValue = subject.isBlank()
            ? Triple.BLANK_NODE_PREFIX + subject.getBlankNodeLabel()
            : Iris.relativize(base, subject.getURI());
        final String predicate = triple.getPredicate().getURI();
        final Node object = triple.getObject();
        if (object.isURI()) {
            return Triple.iri(subjectValue, predicate, Iris.relativize(base, object.getURI()));
        }
        if (object.isBlank()) {
            return Triple.blankNode(subjectValue, predicate, object.getBlankNodeLabel());
        }
        final String language = object.getLiteralLanguage();
        String datatype = object.getLiteralDatatypeURI();
        if ((language != null && !language.isEmpty()) || Rdf.XSD_STRING.equals(datatype)) {
            datatype = null;
        }
        return Triple.literal(subjectValue, predicate, object.getLiteralLexicalForm(), datatype, language);
    }

    /**
     * Builds a new model holding the given triples.
     *
     * @param triples the triples
     * @param base    the server base used to resolve relative IRIs
     * @return the model
     */
    public static Model toModel(final Collection<Triple> triples, final String base) {
        final Model model = ModelFactory.createDefaultModel();
        addTo(model, triples, base);
        return model;
    }

    /**
     * Adds triples to an existing model.
     *
     * @param model   the target model
     * @param triples the triples
     * @param base    the server base used to resolve relative IRIs
     */
    public static void addTo(final Model model, final Collection<Triple> triples, final String base) {
        final Graph graph = model.getGraph();
        final Map<String, Node> blankNodes = new HashMap<>();
        for (final Triple triple : triples) {
            final Node subject = triple.isBlankSubject()
                ? blankNode(blankNodes, triple.getSubject().substring(Triple.BLANK_NODE_PREFIX.length()))
                : NodeFactory.createURI(Iris.resolve(base, triple.getSubject()));
            final Node predicate = NodeFactory.createURI(Iris.resolve(base, triple.getPredicate()));
            graph.add(org.apache.jena.graph.Triple.create(subject, predicate, object(blankNodes, triple, base)));
        }
    }

    private static Node object(final Map<String, Node> blankNodes, final Triple triple, final String base) {
        final TermKind kind = triple.getObjectKind();
        if (kind == TermKind.IRI) {
            return NodeFactory.createURI(Iris.resolve(base, triple.getObject()));
        }
        if (kind == TermKind.BLANK_NODE) {
            return blankNode(blankNodes, triple.getObject());
        }
        if (!triple.getLanguage().isEmpty()) {
            return NodeFactory.createLiteralLang(triple.getObject(), triple.getLanguage());
        }
        final RDFDatatype datatype = triple.getDatatype().isEmpty()
            ? XSDDatatype.XSDstring
            : TypeMapper.getInstance().getSafeTypeByName(triple.getDatatype());
        return NodeFactory.createLiteralDT(triple.getObject(), datatype);
    }

    private static Node blankNode(final Map<String, Node> blankNodes, final String label) {
        return blankNodes.computeIfAbsent(label, NodeFactory::createBlankNode);
    }

}
