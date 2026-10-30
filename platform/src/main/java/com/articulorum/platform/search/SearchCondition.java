package com.articulorum.platform.search;

import com.articulorum.platform.ldp.exception.BadRequestException;

/**
 * One {@code condition} of an {@code fcr:search} request, such as {@code mime_type=image/*} or
 * {@code content_size>=1024}.
 *
 * @param field    the field compared
 * @param operator the comparison
 * @param value    the value compared with (wildcards still escaped as sent)
 */
public record SearchCondition(SearchField field, Operator operator, String value) {

    /**
     * Comparison operators, in the order they are tried when parsing (two-character operators
     * first).
     */
    public enum Operator {

        /** Less than or equal. */
        LESS_OR_EQUAL("<="),

        /** Greater than or equal. */
        GREATER_OR_EQUAL(">="),

        /** Equal; with wildcards a pattern match. */
        EQUAL("="),

        /** Less than. */
        LESS("<"),

        /** Greater than. */
        GREATER(">");

        private final String symbol;

        Operator(final String symbol) {
            this.symbol = symbol;
        }

        /** @return the operator as written in a condition */
        public String symbol() {
            return symbol;
        }

    }

    /**
     * Parses a condition.
     *
     * @param condition the condition, {@code <field><operator><value>}
     * @return the parsed condition
     * @throws BadRequestException when the condition is malformed or names an unknown field
     */
    public static SearchCondition parse(final String condition) {
        if (condition == null || condition.isBlank()) {
            throw new BadRequestException("Empty search condition", null);
        }
        int best = -1;
        Operator found = null;
        for (final Operator operator : Operator.values()) {
            final int index = condition.indexOf(operator.symbol());
            if (index > 0 && (best < 0 || index < best)) {
                best = index;
                found = operator;
            }
        }
        if (found == null) {
            throw new BadRequestException("Search condition without operator: " + condition, null);
        }
        // "<=" and ">=" start at the same index as "<" and ">"; prefer the two-character form
        for (final Operator operator : Operator.values()) {
            if (condition.startsWith(operator.symbol(), best) && operator.symbol().length() > found.symbol().length()) {
                found = operator;
            }
        }
        final String name = condition.substring(0, best).trim();
        final SearchField field = SearchField.fromParameterName(name)
            .orElseThrow(() -> new BadRequestException("Unknown search field: " + name, null));
        final String value = condition.substring(best + found.symbol().length()).trim();
        if (value.isEmpty()) {
            throw new BadRequestException("Search condition without value: " + condition, null);
        }
        if (field == SearchField.RDF_TYPE && found != Operator.EQUAL) {
            throw new BadRequestException("rdf_type only supports the = operator", null);
        }
        return new SearchCondition(field, found, value);
    }

    /**
     * Tells whether the value contains an unescaped {@code *} wildcard.
     *
     * @return {@code true} for a pattern match
     */
    public boolean isPattern() {
        return operator == Operator.EQUAL && field.supportsWildcards() && hasUnescapedStar(value);
    }

    private static boolean hasUnescapedStar(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '*') {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a value with {@code *} wildcards into a SQL {@code LIKE} pattern escaped with
     * {@code \}.
     *
     * @param value the value
     * @return the pattern
     */
    public static String toLikePattern(final String value) {
        final StringBuilder pattern = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                appendLiteral(pattern, value.charAt(++i));
            } else if (c == '*') {
                pattern.append('%');
            } else {
                appendLiteral(pattern, c);
            }
        }
        return pattern.toString();
    }

    /**
     * Removes the escaping backslashes of a value without wildcards.
     *
     * @param value the value
     * @return the literal value
     */
    public static String unescape(final String value) {
        return value.replaceAll("\\\\(.)", "$1");
    }

    private static void appendLiteral(final StringBuilder pattern, final char c) {
        if (c == '%' || c == '_' || c == '\\') {
            pattern.append('\\');
        }
        pattern.append(c);
    }

}
