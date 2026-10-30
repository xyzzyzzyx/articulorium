package com.articulorum.domain.vocabulary;

/**
 * Terms of the Fedora Commons repository vocabulary used for server-managed triples.
 *
 * <p>Articulorum emits the same server-managed metadata as a Fedora 6 repository (creation and
 * modification times and agents, and Fedora resource types) so that tools written for Fedora,
 * such as indexers and the Fedora web UI, work against it unchanged.</p>
 *
 * @see <a href="https://fedora.info/definitions/v4/repository">Fedora repository ontology</a>
 */
public final class Fedora {

    /** The Fedora repository namespace IRI. */
    public static final String NAMESPACE = "http://fedora.info/definitions/v4/repository#";

    /** Conventional prefix for {@link #NAMESPACE}. */
    public static final String PREFIX = "fedora";

    /** Class of every resource managed by the repository. */
    public static final String RESOURCE = NAMESPACE + "Resource";

    /** Class of containers. */
    public static final String CONTAINER = NAMESPACE + "Container";

    /** Class of binaries (LDP non-RDF sources). */
    public static final String BINARY = NAMESPACE + "Binary";

    /** Class of the RDF description of a binary. */
    public static final String NON_RDF_SOURCE_DESCRIPTION = NAMESPACE + "NonRdfSourceDescription";

    /** Creation time of a resource ({@code xsd:dateTime}). */
    public static final String CREATED = NAMESPACE + "created";

    /** Agent that created a resource. */
    public static final String CREATED_BY = NAMESPACE + "createdBy";

    /** Time of the last modification of a resource ({@code xsd:dateTime}). */
    public static final String LAST_MODIFIED = NAMESPACE + "lastModified";

    /** Agent that last modified a resource. */
    public static final String LAST_MODIFIED_BY = NAMESPACE + "lastModifiedBy";

    /** Path segment prefix of Fedora API extensions such as {@code fcr:versions}. */
    public static final String EXTENSION_PREFIX = "fcr:";

    /** {@code xsd:dateTime}, the datatype of {@link #CREATED} and {@link #LAST_MODIFIED}. */
    public static final String XSD_DATE_TIME = Rdf.XSD_NAMESPACE + "dateTime";

    private Fedora() {
        // constants holder
    }

    /**
     * Tells whether an IRI belongs to the Fedora repository namespace. Such predicates and types
     * are server-managed and never stored as client triples.
     *
     * @param iri the IRI, may be {@code null}
     * @return {@code true} for IRIs in {@link #NAMESPACE}
     */
    public static boolean isFedoraTerm(final String iri) {
        return iri != null && iri.startsWith(NAMESPACE);
    }

}
