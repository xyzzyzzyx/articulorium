package com.articulorium.oai.protocol;

/**
 * The error conditions of OAI-PMH 2.0 (section 3.6), answered with HTTP 200 and an
 * {@code <error code="...">} element.
 */
public enum OaiError {

    /** Illegal or missing arguments, or an argument repeated. */
    BAD_ARGUMENT("badArgument"),

    /** The resumption token is invalid or expired. */
    BAD_RESUMPTION_TOKEN("badResumptionToken"),

    /** The verb is missing, repeated or not a verb of the protocol. */
    BAD_VERB("badVerb"),

    /** The metadata format is not supported by the item or the repository. */
    CANNOT_DISSEMINATE_FORMAT("cannotDisseminateFormat"),

    /** The identifier is unknown or illegal in this repository. */
    ID_DOES_NOT_EXIST("idDoesNotExist"),

    /** The combination of from, until, set and metadataPrefix selects no records. */
    NO_RECORDS_MATCH("noRecordsMatch"),

    /** No metadata formats are available for the item. */
    NO_METADATA_FORMATS("noMetadataFormats"),

    /** The repository has no sets. */
    NO_SET_HIERARCHY("noSetHierarchy");

    private final String code;

    OaiError(final String code) {
        this.code = code;
    }

    /** @return the code written in the response, for example {@code badArgument} */
    public String code() {
        return code;
    }

    /**
     * Tells whether the request element of the response must omit its attributes: they are only
     * echoed for requests whose verb and arguments are well formed.
     *
     * @return {@code true} for {@link #BAD_VERB} and {@link #BAD_ARGUMENT}
     */
    public boolean omitsRequestAttributes() {
        return this == BAD_VERB || this == BAD_ARGUMENT;
    }

}
