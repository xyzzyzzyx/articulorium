package com.articulorium.oai.service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.oai.metadata.DcValue;
import com.articulorium.oai.protocol.OaiError;
import com.articulorium.oai.protocol.OaiVerb;

/**
 * The content of an OAI-PMH response, independent of its XML serialisation.
 *
 * @param responseDate when the response was produced
 * @param baseUrl      the repository's base URL
 * @param verb         the verb, or {@code null} when it was not legal
 * @param arguments    the request arguments echoed in the request element, or {@code null} when
 *                     the request was not well formed (badVerb, badArgument)
 * @param error        the error condition, or {@code null} for a successful response
 * @param errorMessage the description of the error, or {@code null}
 * @param body         the verb's answer, or {@code null} for an error
 */
public record OaiResponse(Instant responseDate, String baseUrl, OaiVerb verb, Map<String, String> arguments,
        OaiError error, String errorMessage, Body body) {

    /**
     * Copies the arguments.
     *
     * @param responseDate the response date
     * @param baseUrl      the base URL
     * @param verb         the verb
     * @param arguments    the echoed arguments
     * @param error        the error
     * @param errorMessage the error description
     * @param body         the answer
     */
    public OaiResponse {
        arguments = arguments == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** The answer to one of the six verbs. */
    public sealed interface Body permits Identify, MetadataFormats, Sets, Record, Identifiers, Records {
    }

    /**
     * Identify.
     *
     * @param repositoryName    the repository's name
     * @param baseUrl           its base URL
     * @param earliestDatestamp a lower bound of every datestamp
     * @param adminEmails       administrators' addresses
     */
    public record Identify(String repositoryName, String baseUrl, Instant earliestDatestamp,
            List<String> adminEmails) implements Body {
    }

    /**
     * ListMetadataFormats.
     *
     * @param formats the formats
     */
    public record MetadataFormats(List<MetadataFormat> formats) implements Body {
    }

    /**
     * ListSets.
     *
     * @param sets  the sets of this part
     * @param token the resumption token, or {@code null} for a complete list
     */
    public record Sets(List<SetEntry> sets, Token token) implements Body {
    }

    /**
     * GetRecord.
     *
     * @param record the record
     */
    public record Record(OaiRecord record) implements Body {
    }

    /**
     * ListIdentifiers.
     *
     * @param headers the headers of this part
     * @param token   the resumption token, or {@code null} for a complete list
     */
    public record Identifiers(List<Header> headers, Token token) implements Body {
    }

    /**
     * ListRecords.
     *
     * @param records the records of this part
     * @param token   the resumption token, or {@code null} for a complete list
     */
    public record Records(List<OaiRecord> records, Token token) implements Body {
    }

    /**
     * A metadata format.
     *
     * @param prefix    its metadataPrefix
     * @param schema    the URL of its XML schema
     * @param namespace its XML namespace
     */
    public record MetadataFormat(String prefix, String schema, String namespace) {
    }

    /**
     * A set.
     *
     * @param spec its setSpec
     * @param name its name
     */
    public record SetEntry(String spec, String name) {
    }

    /**
     * A record header.
     *
     * @param identifier the item identifier
     * @param datestamp  the datestamp
     * @param setSpecs   the sets of the item
     */
    public record Header(String identifier, Instant datestamp, List<String> setSpecs) {
    }

    /**
     * A record.
     *
     * @param header   its header
     * @param metadata its oai_dc metadata
     */
    public record OaiRecord(Header header, List<DcValue> metadata) {
    }

    /**
     * The resumption token of an incomplete list.
     *
     * @param value  the token, empty for the last part of a list
     * @param cursor the number of items answered before this part
     */
    public record Token(String value, int cursor) {
    }

}
