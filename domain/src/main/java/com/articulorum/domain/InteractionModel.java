package com.articulorum.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.articulorum.domain.vocabulary.Ldp;

/**
 * The LDP interaction models supported by the platform.
 *
 * <p>An interaction model determines how a resource behaves over HTTP (which methods are allowed,
 * whether it contains other resources, whether its state is RDF or an opaque binary) and which
 * {@code rdf:type} and {@code Link: rel="type"} values the server advertises for it.</p>
 *
 * @see <a href="https://www.w3.org/TR/ldp/#ldpr-gen-linktypehdr">LDP 4.2.1.4</a>
 */
public enum InteractionModel {

    /** An LDP RDF Source that is not a container. */
    RDF_SOURCE(Ldp.RDF_SOURCE),

    /** An LDP Basic Container. */
    BASIC_CONTAINER(Ldp.BASIC_CONTAINER),

    /** An LDP Direct Container. */
    DIRECT_CONTAINER(Ldp.DIRECT_CONTAINER),

    /** An LDP Indirect Container. */
    INDIRECT_CONTAINER(Ldp.INDIRECT_CONTAINER),

    /** An LDP Non-RDF Source (a binary file with an RDF description). */
    NON_RDF_SOURCE(Ldp.NON_RDF_SOURCE);

    private final String iri;

    InteractionModel(final String iri) {
        this.iri = iri;
    }

    /**
     * Returns the most specific LDP class IRI for this interaction model.
     *
     * @return the LDP class IRI, for example {@code ldp:BasicContainer}
     */
    public String getIri() {
        return iri;
    }

    /**
     * Tells whether resources of this model contain other resources.
     *
     * @return {@code true} for basic, direct and indirect containers
     */
    public boolean isContainer() {
        return this == BASIC_CONTAINER || this == DIRECT_CONTAINER || this == INDIRECT_CONTAINER;
    }

    /**
     * Tells whether resources of this model maintain membership triples.
     *
     * @return {@code true} for direct and indirect containers
     */
    public boolean isMembershipContainer() {
        return this == DIRECT_CONTAINER || this == INDIRECT_CONTAINER;
    }

    /**
     * Tells whether the state of resources of this model is RDF.
     *
     * @return {@code false} only for {@link #NON_RDF_SOURCE}
     */
    public boolean isRdfSource() {
        return this != NON_RDF_SOURCE;
    }

    /**
     * Returns every LDP class this model belongs to, from the most general to the most specific.
     * The list is used both for {@code rdf:type} triples and {@code Link: rel="type"} headers.
     *
     * @return an unmodifiable list of class IRIs, always starting with {@code ldp:Resource}
     */
    public List<String> getTypeIris() {
        final List<String> types = new ArrayList<>();
        types.add(Ldp.RESOURCE);
        if (isRdfSource()) {
            types.add(Ldp.RDF_SOURCE);
        }
        if (isContainer()) {
            types.add(Ldp.CONTAINER);
        }
        if (this != RDF_SOURCE) {
            types.add(iri);
        }
        return Collections.unmodifiableList(types);
    }

    /**
     * Resolves an LDP class IRI to an interaction model.
     *
     * <p>{@code ldp:Container} resolves to {@link #BASIC_CONTAINER}, the default container type.
     * {@code ldp:Resource} does not identify a model and resolves to empty.</p>
     *
     * @param iri an LDP class IRI
     * @return the matching interaction model, or empty when the IRI does not name one
     */
    public static Optional<InteractionModel> fromIri(final String iri) {
        if (iri == null) {
            return Optional.empty();
        }
        if (Ldp.CONTAINER.equals(iri)) {
            return Optional.of(BASIC_CONTAINER);
        }
        for (final InteractionModel model : values()) {
            if (model.iri.equals(iri)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

}
