package com.articulorum.transform.spi;

import java.util.Objects;

import com.articulorum.domain.LdpResource;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

/**
 * Everything a {@link Transformer} may use to transform one resource.
 *
 * @param iri      the absolute IRI of the resource; the document identifier
 * @param resource the persisted resource as published by the platform, may be {@code null} for
 *                 previews of arbitrary RDF
 * @param model    the complete RDF representation of the resource (client- and server-managed
 *                 triples) with absolute IRIs
 */
public record TransformationContext(String iri, LdpResource resource, Model model) {

    /**
     * Creates the context.
     *
     * @param iri      the absolute IRI of the resource
     * @param resource the persisted resource, may be {@code null}
     * @param model    the RDF representation
     */
    public TransformationContext {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(model, "model");
    }

    /**
     * Returns the resource as a node of the model.
     *
     * @return the subject node
     */
    public Resource subject() {
        return model.createResource(iri);
    }

}
