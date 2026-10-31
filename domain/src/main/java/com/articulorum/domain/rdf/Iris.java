package com.articulorum.domain.rdf;

import java.util.regex.Pattern;

/**
 * Converts between absolute IRIs and the server-relative IRIs persisted by the platform.
 *
 * <p>The platform stores references to its own resources relative to the server <em>base</em>
 * (scheme, authority and any context or gateway prefix, without a trailing slash, for example
 * {@code http://localhost:9000} or {@code https://example.org/platform}). A relative IRI always
 * starts with {@code /}; an absolute IRI always starts with a scheme. The two are therefore
 * never ambiguous.</p>
 *
 * <pre>
 *   relativize("http://h/platform", "http://h/platform/books/1#it") = "/books/1#it"
 *   relativize("http://h/platform", "http://h/platform")            = "/"
 *   relativize("http://h/platform", "http://example.org/x")         = "http://example.org/x"
 *   resolve("http://h/platform", "/books/1")                        = "http://h/platform/books/1"
 * </pre>
 */
public final class Iris {

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*", Pattern.DOTALL);

    private Iris() {
        // utility class
    }

    /**
     * Normalises a base IRI by removing trailing slashes.
     *
     * @param base the server base IRI
     * @return the base without trailing slashes
     */
    public static String normalizeBase(final String base) {
        String normalized = base == null ? "" : base.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Tells whether a value is a server-relative IRI.
     *
     * @param iri the value to test
     * @return {@code true} when it starts with {@code /}
     */
    public static boolean isRelative(final String iri) {
        return iri != null && iri.startsWith("/");
    }

    /**
     * Tells whether a value is an absolute IRI (it starts with a URI scheme).
     *
     * @param iri the value to test
     * @return {@code true} when it starts with a scheme
     */
    public static boolean isAbsolute(final String iri) {
        return iri != null && SCHEME.matcher(iri).matches();
    }

    /**
     * Relativises an absolute IRI against the server base when it identifies a server resource.
     *
     * @param base the server base IRI
     * @param iri  an absolute IRI
     * @return the server-relative IRI, or {@code iri} unchanged when it lies outside the base
     */
    public static String relativize(final String base, final String iri) {
        final String normalizedBase = normalizeBase(base);
        if (iri == null || normalizedBase.isEmpty() || !iri.startsWith(normalizedBase)) {
            return iri;
        }
        final String rest = iri.substring(normalizedBase.length());
        if (rest.isEmpty()) {
            return "/";
        }
        final char first = rest.charAt(0);
        if (first == '/') {
            return rest;
        }
        if (first == '#' || first == '?') {
            return "/" + rest;
        }
        // e.g. base http://h/platform and IRI http://h/platform2: not below the base
        return iri;
    }

    /**
     * Resolves a server-relative IRI against the server base. Absolute IRIs are returned unchanged.
     *
     * @param base the server base IRI
     * @param iri  a server-relative or absolute IRI
     * @return the absolute IRI
     */
    public static String resolve(final String base, final String iri) {
        if (!isRelative(iri)) {
            return iri;
        }
        return normalizeBase(base) + iri;
    }

}
