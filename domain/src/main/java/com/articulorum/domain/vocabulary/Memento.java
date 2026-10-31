package com.articulorum.domain.vocabulary;

/**
 * Terms of the Memento protocol (RFC 7089) used for resource versions.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7089">RFC 7089: HTTP Framework for Time-Based Access to Resource States</a>
 */
public final class Memento {

    /** The Memento vocabulary namespace. */
    public static final String NAMESPACE = "http://mementoweb.org/ns#";

    /** Class of a resource that has (or may have) mementos. */
    public static final String ORIGINAL_RESOURCE = NAMESPACE + "OriginalResource";

    /** Class of a memento: a fixed prior state of an original resource. */
    public static final String MEMENTO = NAMESPACE + "Memento";

    /** Class of a TimeMap: the list of mementos of an original resource. */
    public static final String TIME_MAP = NAMESPACE + "TimeMap";

    /** Datetime of a memento ({@code xsd:dateTime}). */
    public static final String MEMENTO_DATETIME = NAMESPACE + "mementoDatetime";

    /** HTTP header carrying the datetime of a memento. */
    public static final String MEMENTO_DATETIME_HEADER = "Memento-Datetime";

    /** Link relation of the original resource. */
    public static final String REL_ORIGINAL = "original";

    /** Link relation of the TimeGate (the original resource itself in this server). */
    public static final String REL_TIMEGATE = "timegate";

    /** Link relation of the TimeMap. */
    public static final String REL_TIMEMAP = "timemap";

    /** Link relation of a memento in a TimeMap. */
    public static final String REL_MEMENTO = "memento";

    /** Media type of a TimeMap in link format (RFC 6690). */
    public static final String LINK_FORMAT = "application/link-format";

    private Memento() {
        // constants holder
    }

}
