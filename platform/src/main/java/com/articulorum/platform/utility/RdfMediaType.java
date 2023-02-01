package com.articulorum.platform.utility;

import java.nio.charset.Charset;

import org.springframework.http.MediaType;

public class RdfMediaType {

    public final static MediaType APPLICATION_JSON_LD = new MediaType("application", "ld+json", Charset.defaultCharset()); // JSON-LD
    public final static MediaType APPLICATION_N_TRIPLES = new MediaType("application", "n-triples", Charset.defaultCharset()); // N-TRIPLES
    public final static MediaType APPLICATION_RDF_XML = new MediaType("application", "rdf+xml", Charset.defaultCharset()); // RDF/XML
    public final static MediaType APPLICATION_RDF_JSON = new MediaType("application", "rdf+json", Charset.defaultCharset()); // RDF/JSON
    public final static MediaType APPLICATION_X_TURTLE = new MediaType("application", "x-turtle", Charset.defaultCharset()); // TURTLE

    public final static MediaType TEXT_TURTLE = new MediaType("text", "turtle", Charset.defaultCharset()); // TURTLE
    public final static MediaType TEXT_N3 = new MediaType("text", "n3", Charset.defaultCharset()); // N3
    public final static MediaType TEXT_RDF_N3 = new MediaType("text", "rdf+n3", Charset.defaultCharset()); // N3

}