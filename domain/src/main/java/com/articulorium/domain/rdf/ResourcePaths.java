package com.articulorium.domain.rdf;

import java.util.Optional;

/**
 * Helpers for LDP resource paths.
 *
 * <p>A <em>path</em> is the location of a resource below the server root, with no leading or
 * trailing slash and no empty segments: the root container is {@code ""}, a child is
 * {@code "books"}, a grandchild {@code "books/1"}. A path never contains a query or fragment.</p>
 *
 * <p>The <em>relative IRI</em> of a resource is its path prefixed with {@code /}; it is how the
 * platform stores references to its own resources (see {@link Iris}).</p>
 */
public final class ResourcePaths {

    /** Path separator. */
    public static final String SLASH = "/";

    private ResourcePaths() {
        // utility class
    }

    /**
     * Normalises a path: removes leading and trailing slashes and collapses repeated slashes.
     *
     * @param path a raw path, possibly {@code null}
     * @return the normalised path; never {@code null}
     */
    public static String normalize(final String path) {
        if (path == null) {
            return "";
        }
        final StringBuilder normalized = new StringBuilder(path.length());
        for (final String segment : path.split(SLASH)) {
            if (!segment.isEmpty()) {
                if (normalized.length() > 0) {
                    normalized.append('/');
                }
                normalized.append(segment);
            }
        }
        return normalized.toString();
    }

    /**
     * Tells whether a path taken from a request can be used to address a resource as it is: no
     * {@code .} or {@code ..} segment (which a server resolves to another resource than the one
     * whose access was checked), no query, fragment, back slash, white space, control character or
     * character URIs do not allow, and percent signs only as escapes ({@code %20}).
     *
     * @param path a raw path, possibly {@code null}
     * @return {@code true} when the path is safe to use
     */
    public static boolean isSafe(final String path) {
        if (path == null) {
            return false;
        }
        for (final String segment : path.split(SLASH)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (c <= ' ' || c == 0x7f || "?#\\<>\"{}|^`".indexOf(c) >= 0) {
                return false;
            }
            if (c == '%' && (i + 2 >= path.length() || !hex(path.charAt(i + 1)) || !hex(path.charAt(i + 2)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hex(final char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    /**
     * Returns the parent of a path.
     *
     * @param path a normalised path
     * @return the parent path, or empty for the root path
     */
    public static Optional<String> parentOf(final String path) {
        final String normalized = normalize(path);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        final int index = normalized.lastIndexOf('/');
        return Optional.of(index < 0 ? "" : normalized.substring(0, index));
    }

    /**
     * Returns the last segment of a path.
     *
     * @param path a normalised path
     * @return the last segment; empty for the root path
     */
    public static String nameOf(final String path) {
        final String normalized = normalize(path);
        final int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    /**
     * Appends a child segment to a path.
     *
     * @param parent the parent path
     * @param name   the child segment; must not contain {@code /}
     * @return the child path
     */
    public static String child(final String parent, final String name) {
        final String normalizedParent = normalize(parent);
        final String normalizedName = normalize(name);
        if (normalizedName.isEmpty() || normalizedName.contains(SLASH)) {
            throw new IllegalArgumentException("Invalid path segment: " + name);
        }
        return normalizedParent.isEmpty() ? normalizedName : normalizedParent + SLASH + normalizedName;
    }

    /**
     * Converts a path to its server-relative IRI.
     *
     * @param path a path
     * @return {@code "/"} for the root, otherwise {@code "/" + path}
     */
    public static String toRelativeIri(final String path) {
        return SLASH + normalize(path);
    }

    /**
     * Converts a server-relative IRI back to a resource path. Fragments and query strings are
     * removed, so {@code /books/1#it} maps to {@code books/1}.
     *
     * @param relativeIri an IRI starting with {@code /}
     * @return the path, or empty when the argument is not a server-relative IRI
     */
    public static Optional<String> fromRelativeIri(final String relativeIri) {
        if (!Iris.isRelative(relativeIri)) {
            return Optional.empty();
        }
        String value = relativeIri;
        final int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        final int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return Optional.of(normalize(value));
    }

    /**
     * Tells whether {@code path} is {@code ancestor} or lies below it.
     *
     * @param path     the candidate descendant
     * @param ancestor the candidate ancestor
     * @return {@code true} when {@code path} equals or descends from {@code ancestor}
     */
    public static boolean isSelfOrDescendant(final String path, final String ancestor) {
        final String normalizedPath = normalize(path);
        final String normalizedAncestor = normalize(ancestor);
        return normalizedAncestor.isEmpty()
            || normalizedPath.equals(normalizedAncestor)
            || normalizedPath.startsWith(normalizedAncestor + SLASH);
    }

}
