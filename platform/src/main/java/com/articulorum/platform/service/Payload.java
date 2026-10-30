package com.articulorum.platform.service;

import org.apache.jena.riot.Lang;

import com.articulorum.platform.storage.SpooledUpload;

/**
 * The body of a request that creates or replaces a resource.
 */
public sealed interface Payload permits Payload.Rdf, Payload.Binary, Payload.Empty {

    /**
     * An RDF document, parsed once the target IRI (its base) is known.
     *
     * @param content the raw bytes
     * @param lang    the RDF syntax
     */
    record Rdf(byte[] content, Lang lang) implements Payload {
    }

    /**
     * Opaque bytes that become (or replace) a non-RDF source.
     *
     * @param upload      the spooled bytes with size and digest
     * @param contentType the media type supplied by the client
     * @param filename    the original file name, may be {@code null}
     */
    record Binary(SpooledUpload upload, String contentType, String filename) implements Payload {
    }

    /** No body at all; creates an RDF resource without client-managed triples. */
    record Empty() implements Payload {
    }

}
