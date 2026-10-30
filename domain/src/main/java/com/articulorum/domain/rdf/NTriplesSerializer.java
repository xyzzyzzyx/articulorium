package com.articulorum.domain.rdf;

import java.util.Collection;

import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Rdf;

/**
 * Serialises {@link Triple}s as N-Triples without depending on an RDF library.
 *
 * <p>Server-relative IRIs are resolved against the supplied base so the output only contains
 * absolute IRIs. Literal and IRI escaping follows the
 * <a href="https://www.w3.org/TR/n-triples/">RDF 1.1 N-Triples</a> grammar, which also makes the
 * output safe to embed in a SPARQL {@code INSERT DATA} block.</p>
 */
public final class NTriplesSerializer {

    private NTriplesSerializer() {
        // utility class
    }

    /**
     * Serialises triples as an N-Triples document, one statement per line.
     *
     * @param triples the triples
     * @param base    the server base used to resolve relative IRIs
     * @return the N-Triples document
     */
    public static String serialize(final Collection<Triple> triples, final String base) {
        final StringBuilder out = new StringBuilder();
        for (final Triple triple : triples) {
            out.append(serialize(triple, base)).append('\n');
        }
        return out.toString();
    }

    /**
     * Serialises a single triple as one N-Triples statement (terminated by {@code " ."}).
     *
     * @param triple the triple
     * @param base   the server base used to resolve relative IRIs
     * @return the statement without a trailing newline
     */
    public static String serialize(final Triple triple, final String base) {
        final StringBuilder out = new StringBuilder();
        if (triple.isBlankSubject()) {
            out.append(blankNode(triple.getSubject().substring(Triple.BLANK_NODE_PREFIX.length())));
        } else {
            out.append(iri(Iris.resolve(base, triple.getSubject())));
        }
        out.append(' ').append(iri(Iris.resolve(base, triple.getPredicate()))).append(' ');
        final TermKind kind = triple.getObjectKind();
        if (kind == TermKind.IRI) {
            out.append(iri(Iris.resolve(base, triple.getObject())));
        } else if (kind == TermKind.BLANK_NODE) {
            out.append(blankNode(triple.getObject()));
        } else {
            out.append(literal(triple.getObject(), triple.getDatatype(), triple.getLanguage()));
        }
        return out.append(" .").toString();
    }

    /**
     * Formats an IRI reference, escaping characters N-Triples forbids inside {@code <...>}.
     *
     * @param iri the absolute IRI
     * @return the IRI in angle brackets
     */
    public static String iri(final String iri) {
        final StringBuilder out = new StringBuilder(iri.length() + 2).append('<');
        for (int i = 0; i < iri.length(); i++) {
            final char c = iri.charAt(i);
            if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '{' || c == '}' || c == '|' || c == '^'
                    || c == '`' || c == '\\') {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('>').toString();
    }

    /**
     * Formats a literal with its datatype or language tag.
     *
     * @param lexical  the lexical form
     * @param datatype the datatype IRI, or empty
     * @param language the language tag, or empty
     * @return the literal term
     */
    public static String literal(final String lexical, final String datatype, final String language) {
        final StringBuilder out = new StringBuilder().append('"').append(escapeString(lexical)).append('"');
        if (language != null && !language.isEmpty()) {
            out.append('@').append(language);
        } else if (datatype != null && !datatype.isEmpty() && !Rdf.XSD_STRING.equals(datatype)) {
            out.append("^^").append(iri(datatype));
        }
        return out.toString();
    }

    /**
     * Formats a blank node label, replacing characters not allowed in N-Triples labels.
     *
     * @param label the label
     * @return the blank node term
     */
    public static String blankNode(final String label) {
        final StringBuilder out = new StringBuilder("_:");
        for (int i = 0; i < label.length(); i++) {
            final char c = label.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        if (out.length() == 2) {
            out.append('b');
        }
        return out.toString();
    }

    /**
     * Escapes a string for use inside a double-quoted N-Triples literal.
     *
     * @param value the raw value
     * @return the escaped value
     */
    public static String escapeString(final String value) {
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

}
