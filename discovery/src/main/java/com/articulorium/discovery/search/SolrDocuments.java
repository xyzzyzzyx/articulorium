package com.articulorium.discovery.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.articulorium.discovery.solr.SolrSyntax;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads values of Solr documents, preferring the visitor's language.
 */
final class SolrDocuments {

    private SolrDocuments() {
    }

    /**
     * The values of a field.
     *
     * @param doc   the document
     * @param field the field
     * @return its values as text, in order; empty when it has none
     */
    static List<String> values(final JsonNode doc, final String field) {
        final JsonNode node = doc.path(field);
        final List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isValueNode() && !value.isNull()) {
                    values.add(value.asString());
                }
            });
        } else if (node.isValueNode() && !node.isNull()) {
            values.add(node.asString());
        }
        return values;
    }

    /**
     * The values of a field in the visitor's language, or else in any language.
     *
     * @param doc    the document
     * @param field  the field
     * @param locale the visitor's language tag, may be {@code null}
     * @return the values of {@code <field>_txt_<language>} when there are some, otherwise those of
     *     {@code <field>}
     */
    static List<String> localized(final JsonNode doc, final String field, @Nullable final String locale) {
        final Optional<String> suffix = SolrSyntax.languageSuffix(locale);
        if (suffix.isPresent()) {
            final List<String> values = values(doc, field + "_txt_" + suffix.get());
            if (!values.isEmpty()) {
                return values;
            }
        }
        return values(doc, field);
    }

    /**
     * The first value of a field in the visitor's language, or else in any language.
     *
     * @param doc    the document
     * @param field  the field
     * @param locale the visitor's language tag, may be {@code null}
     * @return the value, {@code null} when there is none
     */
    static @Nullable String first(final JsonNode doc, final String field, @Nullable final String locale) {
        final List<String> values = localized(doc, field, locale);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * The fields to request for a localized field.
     *
     * @param field  the field
     * @param locale the visitor's language tag, may be {@code null}
     * @return the field and, for languages with an analyzer, its language variant
     */
    static List<String> fieldList(final String field, @Nullable final String locale) {
        final List<String> fields = new ArrayList<>();
        fields.add(field);
        SolrSyntax.languageSuffix(locale).ifPresent(suffix -> fields.add(field + "_txt_" + suffix));
        return fields;
    }

    /**
     * A title for a resource without one: the last segment of its path.
     *
     * @param path the path
     * @return the segment
     */
    static String fallbackTitle(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

}
