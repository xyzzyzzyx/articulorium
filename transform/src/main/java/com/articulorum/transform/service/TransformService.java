package com.articulorum.transform.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventAction;
import com.articulorum.event.ResourceEvent;
import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.registry.TransformerRegistry;
import com.articulorum.transform.spi.TransformationContext;
import com.articulorum.transform.spi.TransformationException;
import com.articulorum.transform.spi.Transformer;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Turns resource changes into search documents with the registered transformers.
 */
@Service
public class TransformService {

    private final TransformerRegistry registry;

    private final TransformProperties properties;

    /**
     * Creates the service.
     *
     * @param registry   the transformer registry
     * @param properties transform settings
     */
    public TransformService(final TransformerRegistry registry, final TransformProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Builds the document for a resource change by running every active transformer.
     *
     * <p>For deletions only the identifier is set, so the index can remove the document. The
     * identifier ({@value DocumentEvent#ID_FIELD}) is always the resource's absolute IRI.</p>
     *
     * @param event the change
     * @return the document
     * @throws TransformationException when a transformer fails
     */
    public Map<String, Collection<?>> transform(final ResourceEvent event) {
        final String iri = iriOf(event);
        final Map<String, Collection<?>> document = new LinkedHashMap<>();
        if (event.getAction() != EventAction.DELETED) {
            final TransformationContext context = new TransformationContext(iri, event.getResource(),
                toModel(event.getTriples(), baseOf(event)));
            for (final Transformer transformer : registry.active()) {
                if (transformer.supports(context)) {
                    merge(document, transformer.transform(context));
                }
            }
        }
        document.put(DocumentEvent.ID_FIELD, List.of(iri));
        return document;
    }

    /**
     * Runs one transformer on an RDF representation, without publishing anything.
     *
     * @param name  the transformer name
     * @param iri   the absolute IRI of the resource described by the model
     * @param model the representation
     * @return the document, including the identifier
     * @throws TransformationException when the transformer is unknown or fails
     */
    public Map<String, Collection<?>> preview(final String name, final String iri, final Model model) {
        final Map<String, Collection<?>> document = new LinkedHashMap<>(
            registry.get(name).transform(new TransformationContext(iri, null, model)));
        document.put(DocumentEvent.ID_FIELD, List.of(iri));
        return document;
    }

    /**
     * Returns the base used to resolve the event's server-relative IRIs.
     *
     * @param event the change
     * @return the configured base, or the base the platform reported
     */
    String baseOf(final ResourceEvent event) {
        return StringUtils.hasText(properties.getBaseUri()) ? properties.getBaseUri() : event.getBaseUri();
    }

    /**
     * Returns the absolute IRI of the changed resource.
     *
     * @param event the change
     * @return the IRI
     * @throws TransformationException when the event carries no resource
     */
    String iriOf(final ResourceEvent event) {
        final LdpResource resource = event.getResource();
        if (resource == null) {
            throw new TransformationException("Resource event without resource");
        }
        return Iris.resolve(baseOf(event) == null ? "" : baseOf(event), resource.getRelativeIri());
    }

    /**
     * Builds a Jena model from event triples, resolving server-relative IRIs.
     *
     * @param triples the triples
     * @param base    the base
     * @return the model
     */
    static Model toModel(final List<Triple> triples, final String base) {
        final Model model = ModelFactory.createDefaultModel();
        final Map<String, Resource> blankNodes = new HashMap<>();
        for (final Triple triple : triples) {
            final Resource subject = triple.isBlankSubject()
                ? blankNodes.computeIfAbsent(triple.getSubject(), label -> model.createResource())
                : model.createResource(Iris.resolve(base, triple.getSubject()));
            final RDFNode object = switch (triple.getObjectKind()) {
                case IRI -> model.createResource(Iris.resolve(base, triple.getObject()));
                case BLANK_NODE -> blankNodes.computeIfAbsent(Triple.BLANK_NODE_PREFIX + triple.getObject(),
                    label -> model.createResource());
                case LITERAL -> literal(model, triple);
            };
            model.add(subject, model.createProperty(Iris.resolve(base, triple.getPredicate())), object);
        }
        return model;
    }

    private static RDFNode literal(final Model model, final Triple triple) {
        if (StringUtils.hasText(triple.getLanguage())) {
            return model.createLiteral(triple.getObject(), triple.getLanguage());
        }
        if (StringUtils.hasText(triple.getDatatype())) {
            return model.createTypedLiteral(triple.getObject(), triple.getDatatype());
        }
        return model.createLiteral(triple.getObject());
    }

    /**
     * Merges a document into another: values of fields present in both are combined without
     * duplicates.
     *
     * @param target the document to extend
     * @param source the document to add
     */
    static void merge(final Map<String, Collection<?>> target, final Map<String, Collection<?>> source) {
        source.forEach((field, values) -> {
            final Set<Object> merged = new LinkedHashSet<>();
            if (target.containsKey(field)) {
                merged.addAll(target.get(field));
            }
            merged.addAll(values);
            target.put(field, new ArrayList<>(merged));
        });
    }

}
