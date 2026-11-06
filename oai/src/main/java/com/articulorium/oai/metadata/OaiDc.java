package com.articulorium.oai.metadata;

/**
 * The unqualified Dublin Core format every OAI-PMH repository supports (section 3.4).
 */
public final class OaiDc {

    /** Its metadataPrefix. */
    public static final String PREFIX = "oai_dc";

    /** The namespace of the {@code oai_dc:dc} container element. */
    public static final String NAMESPACE = "http://www.openarchives.org/OAI/2.0/oai_dc/";

    /** The schema of the container. */
    public static final String SCHEMA = "http://www.openarchives.org/OAI/2.0/oai_dc.xsd";

    /** The namespace of the Dublin Core elements. */
    public static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";

    private OaiDc() {
        // constants
    }

}
