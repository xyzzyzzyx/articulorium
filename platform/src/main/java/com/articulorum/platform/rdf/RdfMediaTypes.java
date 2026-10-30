package com.articulorum.platform.rdf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.jena.riot.Lang;
import org.springframework.http.MediaType;

/**
 * RDF media types understood by the platform and their Apache Jena languages.
 *
 * <p>Turtle is the default representation, as required by LDP 4.3.2.1; JSON-LD is supported as
 * recommended by LDP 4.3.2.2.</p>
 *
 * <p>{@code application/json} is accepted for <em>responses</em> (rendered as JSON-LD) as a
 * convenience for generic clients. It is deliberately <em>not</em> treated as RDF in request bodies:
 * a JSON file posted to a container becomes a non-RDF source, as one would expect.</p>
 */
public final class RdfMediaTypes {

    /** {@code text/turtle}. */
    public static final MediaType TEXT_TURTLE = new MediaType("text", "turtle");

    /** {@code application/x-turtle} (legacy Turtle). */
    public static final MediaType APPLICATION_X_TURTLE = new MediaType("application", "x-turtle");

    /** {@code application/ld+json}. */
    public static final MediaType APPLICATION_LD_JSON = new MediaType("application", "ld+json");

    /** {@code application/n-triples}. */
    public static final MediaType APPLICATION_N_TRIPLES = new MediaType("application", "n-triples");

    /** {@code application/rdf+xml}. */
    public static final MediaType APPLICATION_RDF_XML = new MediaType("application", "rdf+xml");

    /** {@code application/rdf+json}. */
    public static final MediaType APPLICATION_RDF_JSON = new MediaType("application", "rdf+json");

    /** {@code text/n3}. */
    public static final MediaType TEXT_N3 = new MediaType("text", "n3");

    /** {@code text/rdf+n3} (legacy N3). */
    public static final MediaType TEXT_RDF_N3 = new MediaType("text", "rdf+n3");

    /** {@code application/sparql-update}, the only PATCH format. */
    public static final MediaType APPLICATION_SPARQL_UPDATE = new MediaType("application", "sparql-update");

    /** Parseable request types, in preference order. */
    private static final Map<String, Lang> READABLE = new LinkedHashMap<>();

    /** Writable response types, in preference order (the first is the default). */
    private static final Map<String, Lang> WRITABLE = new LinkedHashMap<>();

    static {
        READABLE.put(key(TEXT_TURTLE), Lang.TURTLE);
        READABLE.put(key(APPLICATION_LD_JSON), Lang.JSONLD);
        READABLE.put(key(APPLICATION_N_TRIPLES), Lang.NTRIPLES);
        READABLE.put(key(APPLICATION_RDF_XML), Lang.RDFXML);
        READABLE.put(key(APPLICATION_RDF_JSON), Lang.RDFJSON);
        READABLE.put(key(TEXT_N3), Lang.N3);
        READABLE.put(key(TEXT_RDF_N3), Lang.N3);
        READABLE.put(key(APPLICATION_X_TURTLE), Lang.TURTLE);

        WRITABLE.put(key(TEXT_TURTLE), Lang.TURTLE);
        WRITABLE.put(key(APPLICATION_LD_JSON), Lang.JSONLD);
        WRITABLE.put(key(APPLICATION_N_TRIPLES), Lang.NTRIPLES);
        WRITABLE.put(key(APPLICATION_RDF_XML), Lang.RDFXML);
        WRITABLE.put(key(APPLICATION_RDF_JSON), Lang.RDFJSON);
        // N3 is a superset of Turtle; Turtle output is valid N3
        WRITABLE.put(key(TEXT_N3), Lang.TURTLE);
        WRITABLE.put(key(TEXT_RDF_N3), Lang.TURTLE);
        WRITABLE.put(key(APPLICATION_X_TURTLE), Lang.TURTLE);
        WRITABLE.put(key(MediaType.APPLICATION_JSON), Lang.JSONLD);
    }

    private RdfMediaTypes() {
        // constants holder
    }

    private static String key(final MediaType mediaType) {
        return (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the Jena language able to parse a request body of the given type.
     *
     * @param mediaType the request content type (parameters are ignored); may be {@code null}
     * @return the language, or empty when the type is not an RDF syntax
     */
    public static Optional<Lang> readableLang(final MediaType mediaType) {
        return mediaType == null ? Optional.empty() : Optional.ofNullable(READABLE.get(key(mediaType)));
    }

    /**
     * Returns the Jena language used to write a response of the given type.
     *
     * @param mediaType the negotiated content type; may be {@code null}
     * @return the language, or empty when the type is not writable
     */
    public static Optional<Lang> writableLang(final MediaType mediaType) {
        return mediaType == null ? Optional.empty() : Optional.ofNullable(WRITABLE.get(key(mediaType)));
    }

    /**
     * Tells whether a request body of the given type is parsed as RDF.
     *
     * @param mediaType the content type
     * @return {@code true} for RDF syntaxes
     */
    public static boolean isRdf(final MediaType mediaType) {
        return readableLang(mediaType).isPresent();
    }

    /**
     * Lists the writable media types, the default (Turtle) first.
     *
     * @return the writable types
     */
    public static List<MediaType> writableTypes() {
        return WRITABLE.keySet().stream().map(MediaType::parseMediaType).collect(Collectors.toList());
    }

    /**
     * Lists the readable RDF media types as a comma separated header value.
     *
     * @return for example {@code text/turtle, application/ld+json, ...}
     */
    public static String readableTypesHeaderValue() {
        return String.join(", ", READABLE.keySet());
    }

}
