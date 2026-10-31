package com.articulorum.platform.ldp;

/**
 * HTTP header names and link relations used by the Linked Data Platform.
 */
public final class LdpHeaders {

    /** {@code Accept-Post} (LDP 5.2.3.14). */
    public static final String ACCEPT_POST = "Accept-Post";

    /** {@code Accept-Patch} (RFC 5789). */
    public static final String ACCEPT_PATCH = "Accept-Patch";

    /** {@code Slug} (RFC 5023), the client's suggested name for a new resource. */
    public static final String SLUG = "Slug";

    /** {@code Prefer} (RFC 7240). */
    public static final String PREFER = "Prefer";

    /** {@code Preference-Applied} (RFC 7240). */
    public static final String PREFERENCE_APPLIED = "Preference-Applied";

    /** {@code Digest} (RFC 3230), the SHA-256 of a non-RDF source. */
    public static final String DIGEST = "Digest";

    /** Link relation naming an interaction model. */
    public static final String REL_TYPE = "type";

    /** Link relation from a non-RDF source to its description. */
    public static final String REL_DESCRIBED_BY = "describedby";

    /** Link relation from a description to the non-RDF source it describes. */
    public static final String REL_DESCRIBES = "describes";

    private LdpHeaders() {
        // constants holder
    }

}
