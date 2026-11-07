package com.articulorium.discovery.search;

import java.util.Collection;
import java.util.Locale;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;

/**
 * What a resource is, for its presentation: communities and collections list their members,
 * objects are presented in the viewer.
 */
public enum ResourceKind {

    /** A community or sub-community ({@code art:Community}). */
    COMMUNITY(Articulorium.COMMUNITY),

    /** A collection ({@code art:Collection} or {@code pcdm:Collection}). */
    COLLECTION(Articulorium.COLLECTION),

    /** An intellectual entity ({@code pcdm:Object}): a book, an issue, a postcard, a recording. */
    OBJECT(Pcdm.OBJECT),

    /** Any other resource. */
    RESOURCE(null);

    private final String type;

    ResourceKind(final String type) {
        this.type = type;
    }

    /**
     * The RDF type selecting resources of this kind.
     *
     * @return the type IRI, {@code null} for {@link #RESOURCE}
     */
    public String type() {
        return type;
    }

    /**
     * Classifies a resource by its RDF types.
     *
     * @param types the type IRIs
     * @return the kind
     */
    public static ResourceKind of(final Collection<String> types) {
        if (types.contains(Articulorium.COMMUNITY)) {
            return COMMUNITY;
        }
        if (types.contains(Articulorium.COLLECTION) || types.contains(Pcdm.COLLECTION)) {
            return COLLECTION;
        }
        if (types.contains(Pcdm.OBJECT)) {
            return OBJECT;
        }
        return RESOURCE;
    }

    /**
     * Parses a kind of a request.
     *
     * @param value the name in any case
     * @return the kind
     * @throws IllegalArgumentException when the name is unknown
     */
    public static ResourceKind parse(final String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

}
