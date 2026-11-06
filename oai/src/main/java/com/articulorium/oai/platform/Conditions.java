package com.articulorium.oai.platform;

import java.time.Instant;

import com.articulorium.domain.rdf.ResourcePaths;

/**
 * Builds {@code fcr:search} conditions. Paths are sent as {@code info:fedora/<path>} so they do not
 * depend on the base the platform sees, with {@code *} and {@code \} escaped.
 */
public final class Conditions {

    private Conditions() {
        // utility class
    }

    /**
     * Matches resources with a type.
     *
     * @param type the type IRI
     * @return {@code rdf_type=<type>}
     */
    public static String type(final String type) {
        return "rdf_type=" + escape(type);
    }

    /**
     * Matches one resource.
     *
     * @param path the resource path
     * @return {@code fedora_id=info:fedora/<path>}
     */
    public static String path(final String path) {
        return "fedora_id=info:fedora/" + escape(ResourcePaths.normalize(path));
    }

    /**
     * Matches the resources below a container (not the container itself).
     *
     * @param path the container path
     * @return {@code fedora_id=info:fedora/<path>/*}
     */
    public static String below(final String path) {
        return "fedora_id=info:fedora/" + escape(ResourcePaths.normalize(path)) + "/*";
    }

    /**
     * Matches resources whose path sorts at or before a path (in the platform's order).
     *
     * @param path the path
     * @return {@code fedora_id<=info:fedora/<path>}
     */
    public static String pathAtMost(final String path) {
        return "fedora_id<=info:fedora/" + escape(ResourcePaths.normalize(path));
    }

    /**
     * Matches resources modified at an instant.
     *
     * @param instant the instant
     * @return {@code modified=<instant>}
     */
    public static String modifiedAt(final Instant instant) {
        return "modified=" + instant;
    }

    /**
     * Matches resources modified at or after an instant.
     *
     * @param instant the instant
     * @return {@code modified>=<instant>}
     */
    public static String modifiedFrom(final Instant instant) {
        return "modified>=" + instant;
    }

    /**
     * Matches resources modified before an instant.
     *
     * @param instant the instant (exclusive)
     * @return {@code modified<<instant>}
     */
    public static String modifiedBefore(final Instant instant) {
        return "modified<" + instant;
    }

    /**
     * Escapes the wildcard and escape characters of a condition value.
     *
     * @param value the literal value
     * @return the escaped value
     */
    static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*");
    }

}
