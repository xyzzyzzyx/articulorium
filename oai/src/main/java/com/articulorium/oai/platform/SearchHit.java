package com.articulorium.oai.platform;

import java.time.Instant;
import java.util.Set;

/**
 * One resource of a platform search result.
 *
 * @param path     the resource path below the LDP root
 * @param modified when the resource last changed (full precision, as the platform stores it)
 * @param types    every {@code rdf:type} of the resource
 */
public record SearchHit(String path, Instant modified, Set<String> types) {

    /**
     * Copies the types.
     *
     * @param path     the path
     * @param modified the modification time
     * @param types    the types
     */
    public SearchHit {
        types = Set.copyOf(types);
    }

    /**
     * Tells whether the resource has a type.
     *
     * @param type the type IRI
     * @return {@code true} when it has the type
     */
    public boolean hasType(final String type) {
        return types.contains(type);
    }

}
