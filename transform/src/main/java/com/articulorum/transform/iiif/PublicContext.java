package com.articulorum.transform.iiif;

/**
 * How the caller sees the system: the public bases of the platform, the user interface and this
 * service, and the caller's credentials.
 *
 * @param serviceBase   public base of this service, ending with {@code /} (for example
 *                      {@code http://localhost:8000/transform/})
 * @param platformBase  public base of the platform's LDP root, ending with {@code /}
 * @param uiBase        public base of the user interface, ending with {@code /}
 * @param authorization the caller's {@code Authorization} header, may be {@code null}
 */
public record PublicContext(String serviceBase, String platformBase, String uiBase, String authorization) {

    /**
     * Returns the public IRI of a platform resource.
     *
     * @param path the resource path
     * @return the IRI
     */
    public String resourceIri(final String path) {
        return platformBase + path;
    }

    /**
     * Returns the path of a public platform IRI.
     *
     * @param iri the IRI
     * @return the path, or {@code null} when the IRI is not below the platform base
     */
    public String pathOf(final String iri) {
        if (iri == null || !iri.startsWith(platformBase)) {
            return iri != null && (iri + "/").equals(platformBase) ? "" : null;
        }
        String path = iri.substring(platformBase.length());
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Returns the IIIF URL of a resource.
     *
     * @param path the resource path
     * @return {@code <service>/iiif/<path>}
     */
    public String iiifUrl(final String path) {
        return serviceBase + "iiif/" + path;
    }

    /**
     * Returns the user interface page of a resource.
     *
     * @param path the resource path
     * @return {@code <ui>/browse/<path>}
     */
    public String uiUrl(final String path) {
        return uiBase + "browse/" + path;
    }

}
