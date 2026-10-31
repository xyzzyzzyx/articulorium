package com.articulorum.platform.ldp;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.articulorum.domain.vocabulary.Ldp;

/**
 * The representation preferences a client expressed with the {@code Prefer} header
 * (RFC 7240 and LDP 4.2.2 / 7.2).
 *
 * <p>Only the {@code return=representation} preference with {@code include}/{@code omit}
 * parameters is interpreted. By default containers are rendered with containment and membership
 * triples.</p>
 *
 * @param includeContainment whether {@code ldp:contains} triples are rendered
 * @param includeMembership  whether membership triples are rendered
 * @param applied            whether the client expressed a preference the server honoured
 */
public record Preference(boolean includeContainment, boolean includeMembership, boolean applied) {

    /** The default: every server-managed triple is included. */
    public static final Preference DEFAULT = new Preference(true, true, false);

    /** Header value returned in {@code Preference-Applied} when a preference was honoured. */
    public static final String APPLIED_HEADER_VALUE = "return=representation";

    /**
     * Interprets {@code Prefer} header values.
     *
     * @param headerValues the raw header values; may be {@code null}
     * @return the preference
     */
    public static Preference parse(final Collection<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return DEFAULT;
        }
        boolean representation = false;
        final Set<String> include = new HashSet<>();
        final Set<String> omit = new HashSet<>();
        for (final String headerValue : headerValues) {
            if (headerValue == null) {
                continue;
            }
            // a header may hold several comma separated preferences
            for (final String preference : headerValue.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                final String[] parts = preference.split(";");
                final String first = parts[0].trim().toLowerCase(Locale.ROOT).replace(" ", "");
                if (!"return=representation".equals(first)) {
                    continue;
                }
                representation = true;
                for (int i = 1; i < parts.length; i++) {
                    final String parameter = parts[i].trim();
                    final int equals = parameter.indexOf('=');
                    if (equals < 0) {
                        continue;
                    }
                    final String name = parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    final String value = unquote(parameter.substring(equals + 1).trim());
                    final Set<String> target = "include".equals(name) ? include : "omit".equals(name) ? omit : null;
                    if (target != null) {
                        for (final String token : value.split("\\s+")) {
                            if (!token.isEmpty()) {
                                target.add(token);
                            }
                        }
                    }
                }
            }
        }
        if (!representation || (include.isEmpty() && omit.isEmpty())) {
            return DEFAULT;
        }
        final boolean minimal = include.contains(Ldp.PREFER_MINIMAL_CONTAINER) || include.contains(Ldp.PREFER_EMPTY_CONTAINER);
        boolean containment = !minimal || include.contains(Ldp.PREFER_CONTAINMENT);
        boolean membership = !minimal || include.contains(Ldp.PREFER_MEMBERSHIP);
        if (omit.contains(Ldp.PREFER_CONTAINMENT)) {
            containment = false;
        }
        if (omit.contains(Ldp.PREFER_MEMBERSHIP)) {
            membership = false;
        }
        if (omit.contains(Ldp.PREFER_MINIMAL_CONTAINER) || omit.contains(Ldp.PREFER_EMPTY_CONTAINER)) {
            containment = true;
            membership = true;
        }
        return new Preference(containment, membership, true);
    }

    private static String unquote(final String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

}
