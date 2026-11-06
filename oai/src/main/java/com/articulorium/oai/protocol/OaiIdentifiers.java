package com.articulorium.oai.protocol;

import java.util.Optional;

import com.articulorium.domain.rdf.ResourcePaths;

/**
 * Item identifiers of the repository, {@code oai:<repository-identifier>:<path>}: the resource path
 * (below the LDP root) qualified by the repository's namespace, so identifiers do not depend on the
 * host name harvesters use.
 *
 * @param namespace the repository identifier, for example {@code articulorium.org}
 */
public record OaiIdentifiers(String namespace) {

    /**
     * Returns the identifier of a resource.
     *
     * @param path the resource path
     * @return for example {@code oai:articulorium.org:communities/arts/moby-dick}
     */
    public String identifier(final String path) {
        return prefix() + ResourcePaths.normalize(path);
    }

    /**
     * Returns the resource path of an identifier.
     *
     * @param identifier the identifier
     * @return the path, or empty when the identifier is not one of this repository
     */
    public Optional<String> path(final String identifier) {
        if (identifier == null || !identifier.startsWith(prefix())) {
            return Optional.empty();
        }
        final String path = identifier.substring(prefix().length());
        return path.isEmpty() || !path.equals(ResourcePaths.normalize(path)) ? Optional.empty() : Optional.of(path);
    }

    private String prefix() {
        return "oai:" + namespace + ":";
    }

}
