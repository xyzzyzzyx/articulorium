package com.articulorium.discovery.config;

import java.net.URI;

import org.springframework.http.HttpHeaders;

/**
 * {@code X-Forwarded-*} headers describing a public base to another service, so it mints the
 * public URLs visitors use (the platform its resource IRIs, the transform service its IIIF URLs)
 * although it is reached at an internal address.
 */
public final class ForwardedHeaders {

    private ForwardedHeaders() {
    }

    /**
     * Builds the headers of a public base.
     *
     * @param base the public base, for example {@code http://localhost:8000/platform/}
     * @return {@code X-Forwarded-Proto}, {@code -Host}, {@code -Port} and, below the root,
     *     {@code -Prefix}
     */
    public static HttpHeaders of(final URI base) {
        final HttpHeaders headers = new HttpHeaders();
        final String scheme = base.getScheme() == null ? "http" : base.getScheme();
        final int port = base.getPort() >= 0 ? base.getPort() : ("https".equals(scheme) ? 443 : 80);
        headers.set("X-Forwarded-Proto", scheme);
        headers.set("X-Forwarded-Host", base.getHost());
        headers.set("X-Forwarded-Port", Integer.toString(port));
        final String path = base.getRawPath();
        if (path != null && path.length() > 1) {
            headers.set("X-Forwarded-Prefix", path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        return headers;
    }

}
