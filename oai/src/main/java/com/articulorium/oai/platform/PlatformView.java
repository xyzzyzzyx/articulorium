package com.articulorium.oai.platform;

import java.util.Objects;

/**
 * How one harvester sees the platform: the public base under which resource IRIs are minted and
 * the harvester's credentials.
 *
 * @param base          public base of the platform's LDP root, ending with {@code /} (for example
 *                      {@code http://localhost:8000/platform/})
 * @param authorization the harvester's {@code Authorization} header, may be {@code null}
 */
public record PlatformView(String base, String authorization) {

    /**
     * Validates the view.
     *
     * @param base          the public base
     * @param authorization the credentials, may be {@code null}
     */
    public PlatformView {
        Objects.requireNonNull(base, "base");
        if (!base.endsWith("/")) {
            base = base + "/";
        }
    }

    /**
     * Returns the public IRI of a resource.
     *
     * @param path the resource path
     * @return the IRI
     */
    public String iri(final String path) {
        return base + path;
    }

    /**
     * Returns the path of a public resource IRI.
     *
     * @param iri the IRI
     * @return the path, or {@code null} when the IRI is not below the base
     */
    public String pathOf(final String iri) {
        if (iri == null || !iri.startsWith(base)) {
            return null;
        }
        String path = iri.substring(base.length());
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

}
