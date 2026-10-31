package com.articulorum.platform.ldp;

import com.articulorum.domain.LdpResource;

/**
 * Entity tags for LDP resources and HTTP precondition evaluation.
 *
 * <p>Every resource has one strong entity tag derived from its identifier and its optimistic
 * locking version. The version changes whenever the resource's state or any of its
 * server-managed triples (containment, membership) changes, so the tag changes with every
 * representation (LDP 4.2.1.3).</p>
 *
 * <p>Comparison is deliberately lenient about the weak prefix {@code W/}: some HTTP
 * intermediaries weaken tags on compression, which would otherwise make {@code If-Match}
 * impossible to satisfy.</p>
 */
public final class EntityTags {

    private EntityTags() {
        // utility class
    }

    /**
     * Computes the entity tag of a resource.
     *
     * @param resource a persisted resource
     * @return the quoted entity tag
     */
    public static String of(final LdpResource resource) {
        final long version = resource.getVersion() == null ? 0L : resource.getVersion();
        return "\"" + resource.getId() + "-" + version + "\"";
    }

    /**
     * Evaluates an {@code If-Match} or {@code If-None-Match} header against a current tag.
     *
     * @param header  the header value, for example {@code "a", "b"} or {@code *}
     * @param current the current quoted entity tag
     * @return {@code true} when the header lists the current tag or is {@code *}
     */
    public static boolean matches(final String header, final String current) {
        if (header == null) {
            return false;
        }
        final String normalizedCurrent = strip(current);
        for (final String candidate : header.split(",")) {
            final String tag = candidate.trim();
            if ("*".equals(tag) || strip(tag).equals(normalizedCurrent)) {
                return true;
            }
        }
        return false;
    }

    private static String strip(final String tag) {
        String value = tag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        return value;
    }

}
