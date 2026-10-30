package com.articulorum.domain;

/**
 * The kind of RDF term stored in the object position of a {@link Triple}.
 */
public enum TermKind {

    /** An IRI, stored either absolute or relative to the server base (starting with {@code /}). */
    IRI,

    /** A blank node, stored by its label (without the {@code _:} prefix). */
    BLANK_NODE,

    /** A literal, stored by its lexical form with optional datatype and language tag. */
    LITERAL

}
