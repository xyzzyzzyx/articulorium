package com.articulorum.platform.ldp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A single link from an HTTP {@code Link} header (RFC 8288), with parsing and formatting helpers.
 *
 * @param target    the link target IRI
 * @param relations the relation types, lower-cased when they are registered tokens
 */
public record LinkHeader(String target, Set<String> relations) {

    /**
     * Formats a link with one relation.
     *
     * @param target   the target IRI
     * @param relation the relation type
     * @return the header value, for example {@code <http://www.w3.org/ns/ldp#Resource>; rel="type"}
     */
    public static String format(final String target, final String relation) {
        return "<" + target + ">; rel=\"" + relation + "\"";
    }

    /**
     * Parses every link found in a collection of {@code Link} header values.
     *
     * @param headerValues the raw header values; may be {@code null}
     * @return the links, in order; malformed entries are skipped
     */
    public static List<LinkHeader> parse(final Collection<String> headerValues) {
        final List<LinkHeader> links = new ArrayList<>();
        if (headerValues == null) {
            return links;
        }
        for (final String value : headerValues) {
            if (value != null) {
                for (final String part : splitLinks(value)) {
                    parseLink(part).ifPresent(links::add);
                }
            }
        }
        return links;
    }

    /**
     * Returns the targets of all links having the given relation.
     *
     * @param headerValues the raw header values
     * @param relation     the relation type (case-insensitive)
     * @return the matching targets, in order
     */
    public static List<String> targets(final Collection<String> headerValues, final String relation) {
        final String wanted = relation.toLowerCase(Locale.ROOT);
        final List<String> targets = new ArrayList<>();
        for (final LinkHeader link : parse(headerValues)) {
            if (link.relations().contains(wanted)) {
                targets.add(link.target());
            }
        }
        return targets;
    }

    /** Splits a header value on commas that are outside {@code <...>} and quoted strings. */
    private static List<String> splitLinks(final String value) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inTarget = false;
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '<' && !inQuotes) {
                inTarget = true;
            } else if (c == '>' && !inQuotes) {
                inTarget = false;
            } else if (c == '"' && !inTarget) {
                inQuotes = !inQuotes;
            }
            if (c == ',' && !inTarget && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static Optional<LinkHeader> parseLink(final String part) {
        final String trimmed = part.trim();
        final int open = trimmed.indexOf('<');
        final int close = trimmed.indexOf('>', open + 1);
        if (open != 0 || close < 0) {
            return Optional.empty();
        }
        final String target = trimmed.substring(open + 1, close).trim();
        final Set<String> relations = new LinkedHashSet<>();
        for (final String parameter : trimmed.substring(close + 1).split(";")) {
            final int equals = parameter.indexOf('=');
            if (equals < 0) {
                continue;
            }
            final String name = parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String parameterValue = parameter.substring(equals + 1).trim();
            if (parameterValue.length() >= 2 && parameterValue.startsWith("\"") && parameterValue.endsWith("\"")) {
                parameterValue = parameterValue.substring(1, parameterValue.length() - 1);
            }
            if ("rel".equals(name)) {
                for (final String relation : parameterValue.trim().split("\\s+")) {
                    if (!relation.isEmpty()) {
                        // registered relation types are case-insensitive; extension relations are IRIs
                        relations.add(relation.contains(":") ? relation : relation.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return Optional.of(new LinkHeader(target, Set.copyOf(relations)));
    }

}
