package com.articulorium.discovery.solr;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Solr query syntax for values that come from visitors: they are always quoted or passed to the
 * {@code term} parser, never interpreted as query syntax.
 */
public final class SolrSyntax {

    /**
     * Languages with a {@code *_txt_<suffix>} dynamic field in Solr's {@code _default} config set,
     * by ISO 639-1 code: the same table as the transform service's field-mapping transformer, which
     * writes these fields.
     */
    static final Map<String, String> LANGUAGES = Map.ofEntries(
        Map.entry("ar", "ar"), Map.entry("bg", "bg"), Map.entry("ca", "ca"), Map.entry("cs", "cz"),
        Map.entry("da", "da"), Map.entry("de", "de"), Map.entry("el", "el"), Map.entry("en", "en"),
        Map.entry("es", "es"), Map.entry("et", "et"), Map.entry("eu", "eu"), Map.entry("fa", "fa"),
        Map.entry("fi", "fi"), Map.entry("fr", "fr"), Map.entry("ga", "ga"), Map.entry("gl", "gl"),
        Map.entry("hi", "hi"), Map.entry("hu", "hu"), Map.entry("hy", "hy"), Map.entry("id", "id"),
        Map.entry("it", "it"), Map.entry("ja", "ja"), Map.entry("ko", "cjk"), Map.entry("lv", "lv"),
        Map.entry("nl", "nl"), Map.entry("nb", "no"), Map.entry("nn", "no"), Map.entry("no", "no"),
        Map.entry("pt", "pt"), Map.entry("ro", "ro"), Map.entry("ru", "ru"), Map.entry("sv", "sv"),
        Map.entry("th", "th"), Map.entry("tr", "tr"), Map.entry("zh", "cjk"));

    private SolrSyntax() {
    }

    /**
     * Quotes a value as a phrase.
     *
     * @param value the value
     * @return the value in double quotes, with {@code \} and {@code "} escaped
     */
    public static String phrase(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * A query matching any of several exact values of a field.
     *
     * @param field  the field (from the settings, never from visitors)
     * @param values the values
     * @return for example {@code type_str:("a" OR "b")}
     */
    public static String anyOf(final String field, final Collection<String> values) {
        return field + ":(" + values.stream().map(SolrSyntax::phrase).collect(Collectors.joining(" OR ")) + ")";
    }

    /**
     * A filter query with a tag, so facet counts can exclude it.
     *
     * @param tag   the tag (a facet identifier from the settings)
     * @param query the query
     * @return {@code {!tag=<tag>}<query>}
     */
    public static String tagged(final String tag, final String query) {
        return "{!tag=" + tag + "}" + query;
    }

    /**
     * A query matching one exact value, parsed by Solr's {@code term} parser (no syntax at all).
     *
     * @param field the field (from the settings)
     * @param value the value
     * @return {@code {!term f=<field>}<value>}
     */
    public static String term(final String field, final String value) {
        return "{!term f=" + field + "}" + value;
    }

    /**
     * A query matching the direct children of containers in a hierarchy of identifiers: values that
     * continue a parent's value with {@code /} and one more segment. Platform paths are
     * hierarchical, so the members of a community or collection are the documents whose
     * identifier is one segment below its own.
     *
     * @param field   the field of identifiers (from the settings)
     * @param parents the parents' identifiers (one resource under several addresses)
     * @return for example {@code (id:/http\:\/\/x\/c\/[^\/]+/)}, a regular expression per parent
     */
    public static String childrenOf(final String field, final Collection<String> parents) {
        return parents.stream()
            .map(parent -> field + ":/" + regex(parent.endsWith("/") ? parent : parent + "/") + "[^\\/]+/")
            .collect(Collectors.joining(" OR ", "(", ")"));
    }

    /**
     * Escapes a value for a Lucene regular expression in a query: every character that is not a
     * letter or a digit is escaped, the {@code /} delimiters included.
     *
     * @param value the literal value
     * @return the escaped value
     */
    static String regex(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() * 2);
        value.codePoints().forEach(c -> {
            if (!Character.isLetterOrDigit(c)) {
                escaped.append('\\');
            }
            escaped.appendCodePoint(c);
        });
        return escaped.toString();
    }

    /**
     * The suffix of a language's text fields.
     *
     * @param tag a BCP 47 tag
     * @return for example {@code de} for {@code de-AT}, {@code cjk} for {@code zh-CN}; empty for
     *     languages without an analyzer
     */
    public static Optional<String> languageSuffix(final String tag) {
        return tag == null ? Optional.empty()
            : Optional.ofNullable(LANGUAGES.get(Locale.forLanguageTag(tag).getLanguage()));
    }

}
