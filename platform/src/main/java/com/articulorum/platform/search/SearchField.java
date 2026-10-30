package com.articulorum.platform.search;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fields of the {@code fcr:search} API, named as in Fedora 6.
 */
public enum SearchField {

    /** The resource IRI (or path); supports {@code *} wildcards with {@code =}. */
    FEDORA_ID("fedora_id", "path", true),

    /** Creation time; ISO-8601 instant or date. */
    CREATED("created", "created", false),

    /** Last modification time; ISO-8601 instant or date. */
    MODIFIED("modified", "modified", false),

    /** Size in bytes of a non-RDF source. */
    CONTENT_SIZE("content_size", "binary.size", false),

    /** Media type of a non-RDF source; supports {@code *} wildcards with {@code =}. */
    MIME_TYPE("mime_type", "binary.contentType", true),

    /** An {@code rdf:type} of the resource (LDP, Fedora or client type); supports wildcards with {@code =}. */
    RDF_TYPE("rdf_type", null, true);

    private final String parameterName;

    private final String attribute;

    private final boolean wildcards;

    SearchField(final String parameterName, final String attribute, final boolean wildcards) {
        this.parameterName = parameterName;
        this.attribute = attribute;
        this.wildcards = wildcards;
    }

    /** @return the name used in conditions and result items, for example {@code fedora_id} */
    public String parameterName() {
        return parameterName;
    }

    /** @return the JPA attribute path the field maps to, or {@code null} when it is computed */
    public String attribute() {
        return attribute;
    }

    /** @return {@code true} when {@code *} wildcards are supported */
    public boolean supportsWildcards() {
        return wildcards;
    }

    /**
     * Looks a field up by its parameter name, ignoring case.
     *
     * @param name the parameter name
     * @return the field, if any
     */
    public static Optional<SearchField> fromParameterName(final String name) {
        return Arrays.stream(values()).filter(field -> field.parameterName.equalsIgnoreCase(name)).findFirst();
    }

}
