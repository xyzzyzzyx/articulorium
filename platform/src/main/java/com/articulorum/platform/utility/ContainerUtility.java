package com.articulorum.platform.utility;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.validator.routines.UrlValidator;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.articulorum.domain.Container;
import com.articulorum.domain.Element;

public class ContainerUtility {

    private final static UrlValidator URL_VALIDATOR = new UrlValidator(new String[] { "http", "https" }, UrlValidator.ALLOW_LOCAL_URLS);

    private ContainerUtility() {

    }

    public static Container fromModel(final Model model) throws IOException {
        final Container container = new Container();
        final Iterable<Statement> statementIterable = () -> model.listStatements();
        final Stream<Statement> statements = StreamSupport.stream(statementIterable.spliterator(), false);
        final Set<Element> elements = statements.map(statement -> {
            final Property predicate = statement.getPredicate();
            final RDFNode object = statement.getObject();
            return new Element(predicate.toString(), object.toString());
        }).collect(Collectors.toSet());
        container.setElements(elements);
        return container;
    }

    public static Model toModel(final Container container) throws IOException {
        final Model model = ModelFactory.createDefaultModel();
        final String uri = ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString();
        final Resource subject = ResourceFactory.createResource(uri);
        container.getElements().forEach(element -> {
            final Property predicate = ResourceFactory.createProperty(element.getAttribute());
            final Node node = URL_VALIDATOR.isValid(element.getValue())
                ? NodeFactory.createURI(element.getValue())
                : NodeFactory.createLiteral(element.getValue());
            model.add(subject, predicate, model.asRDFNode(node));
            checkPrefix(model, element.getAttribute());
            checkPrefix(model, element.getValue());
        });
        return model;
    }

    public static void checkPrefix(Model model, String iri) {
        Optional<Entry<String, String>> entry = PrefixUtility.find(iri);
        if (entry.isPresent()) {
            model.setNsPrefix(entry.get().getValue(), entry.get().getKey());
        }
    }

}