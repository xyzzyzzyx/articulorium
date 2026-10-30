package com.articulorum.platform.web;

import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.ResourcePaths;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the server base IRI and the resource path from a servlet request.
 *
 * <p>With {@code server.forward-headers-strategy=framework} Spring's {@code ForwardedHeaderFilter}
 * has already applied {@code X-Forwarded-Proto}, {@code -Host}, {@code -Port} and {@code -Prefix}
 * (set by the gateway) to the request, so the base reflects the IRI the client actually used,
 * for example {@code http://localhost:8000/platform}.</p>
 */
public final class RequestTargets {

    private RequestTargets() {
        // utility class
    }

    /**
     * Returns the server base: scheme, authority and context path, without a trailing slash.
     *
     * @param request the request
     * @return the base IRI
     */
    public static String base(final HttpServletRequest request) {
        return Iris.normalizeBase(ServletUriComponentsBuilder.fromContextPath(request)
            .replaceQuery(null)
            .fragment(null)
            .build()
            .toUriString());
    }

    /**
     * Returns the normalised resource path of the request (still percent-encoded, as sent).
     *
     * @param request the request
     * @return the path, empty for the root container
     */
    public static String path(final HttpServletRequest request) {
        String uri = request.getRequestURI();
        final String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        final int matrix = uri.indexOf(';');
        if (matrix >= 0) {
            uri = uri.substring(0, matrix);
        }
        return ResourcePaths.normalize(uri);
    }

}
