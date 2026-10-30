package com.articulorum.domain.vocabulary;

/**
 * PREMIS and EBUCore terms used for the server-managed technical metadata of non-RDF sources.
 */
public final class Premis {

    /** The PREMIS RDF namespace. */
    public static final String NAMESPACE = "http://www.loc.gov/premis/rdf/v1#";

    /** Size of a file in bytes. */
    public static final String HAS_SIZE = NAMESPACE + "hasSize";

    /** Message digest of a file, as a {@code urn:sha-256:...} IRI. */
    public static final String HAS_MESSAGE_DIGEST = NAMESPACE + "hasMessageDigest";

    /** The EBUCore namespace. */
    public static final String EBUCORE_NAMESPACE = "http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#";

    /** Media type of a file. */
    public static final String HAS_MIME_TYPE = EBUCORE_NAMESPACE + "hasMimeType";

    /** Original file name. */
    public static final String FILENAME = EBUCORE_NAMESPACE + "filename";

    /** Prefix of digest IRIs. */
    public static final String SHA256_URN_PREFIX = "urn:sha-256:";

    /** {@code xsd:long}, the datatype of {@link #HAS_SIZE}. */
    public static final String XSD_LONG = Rdf.XSD_NAMESPACE + "long";

    private Premis() {
        // constants holder
    }

}
