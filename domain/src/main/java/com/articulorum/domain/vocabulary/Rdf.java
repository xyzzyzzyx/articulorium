package com.articulorum.domain.vocabulary;

/**
 * Terms from the RDF, RDF Schema and XML Schema vocabularies used by the platform.
 */
public final class Rdf {

    /** The RDF namespace IRI. */
    public static final String NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** The {@code rdf:type} predicate. */
    public static final String TYPE = NAMESPACE + "type";

    /** Datatype of language-tagged string literals ({@code rdf:langString}). */
    public static final String LANG_STRING = NAMESPACE + "langString";

    /** The XML Schema datatype namespace. */
    public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";

    /** Default datatype of plain literals ({@code xsd:string}). */
    public static final String XSD_STRING = XSD_NAMESPACE + "string";

    /** The RDF Schema namespace IRI. */
    public static final String RDFS_NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";

    /** The {@code rdfs:label} predicate. */
    public static final String RDFS_LABEL = RDFS_NAMESPACE + "label";

    private Rdf() {
        // constants holder
    }

}
