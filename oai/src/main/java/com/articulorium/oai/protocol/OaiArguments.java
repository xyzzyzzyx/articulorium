package com.articulorium.oai.protocol;

/**
 * Names of the OAI-PMH request arguments.
 */
public final class OaiArguments {

    /** The verb. */
    public static final String VERB = "verb";

    /** An item's unique identifier. */
    public static final String IDENTIFIER = "identifier";

    /** A metadata format. */
    public static final String METADATA_PREFIX = "metadataPrefix";

    /** Lower bound of datestamp-based selective harvesting (inclusive). */
    public static final String FROM = "from";

    /** Upper bound of datestamp-based selective harvesting (inclusive). */
    public static final String UNTIL = "until";

    /** Set-based selective harvesting. */
    public static final String SET = "set";

    /** Continues an incomplete list (exclusive argument). */
    public static final String RESUMPTION_TOKEN = "resumptionToken";

    private OaiArguments() {
        // constants
    }

}
