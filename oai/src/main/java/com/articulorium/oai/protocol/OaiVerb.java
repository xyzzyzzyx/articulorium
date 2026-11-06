package com.articulorium.oai.protocol;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The six OAI-PMH 2.0 verbs and their arguments (section 4).
 */
public enum OaiVerb {

    /** Information about the repository. */
    IDENTIFY("Identify", Set.of(), Set.of(), false),

    /** The metadata formats of the repository or of one item. */
    LIST_METADATA_FORMATS("ListMetadataFormats", Set.of(), Set.of(OaiArguments.IDENTIFIER), false),

    /** The set structure of the repository. */
    LIST_SETS("ListSets", Set.of(), Set.of(), true),

    /** One record. */
    GET_RECORD("GetRecord", Set.of(OaiArguments.IDENTIFIER, OaiArguments.METADATA_PREFIX), Set.of(), false),

    /** Headers of selected records. */
    LIST_IDENTIFIERS("ListIdentifiers", Set.of(OaiArguments.METADATA_PREFIX),
        Set.of(OaiArguments.FROM, OaiArguments.UNTIL, OaiArguments.SET), true),

    /** Selected records. */
    LIST_RECORDS("ListRecords", Set.of(OaiArguments.METADATA_PREFIX),
        Set.of(OaiArguments.FROM, OaiArguments.UNTIL, OaiArguments.SET), true);

    private final String value;

    private final Set<String> required;

    private final Set<String> optional;

    private final boolean resumable;

    OaiVerb(final String value, final Set<String> required, final Set<String> optional, final boolean resumable) {
        this.value = value;
        this.required = required;
        this.optional = optional;
        this.resumable = resumable;
    }

    /** @return the verb as written in requests, for example {@code ListRecords} */
    public String value() {
        return value;
    }

    /** @return the arguments the verb requires (unless resumed) */
    public Set<String> required() {
        return required;
    }

    /** @return the arguments the verb accepts besides the required ones */
    public Set<String> optional() {
        return optional;
    }

    /** @return whether the verb accepts the exclusive {@code resumptionToken} argument */
    public boolean resumable() {
        return resumable;
    }

    /**
     * Looks a verb up; verbs are case sensitive.
     *
     * @param value the verb as sent
     * @return the verb, if it is one
     */
    public static Optional<OaiVerb> of(final String value) {
        return Arrays.stream(values()).filter(verb -> verb.value.equals(value)).findFirst();
    }

}
