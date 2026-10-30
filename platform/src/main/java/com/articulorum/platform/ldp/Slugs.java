package com.articulorum.platform.ldp;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns a client supplied {@code Slug} (or file name) into a safe path segment.
 *
 * <p>The result only contains RFC 3986 unreserved characters ({@code A-Z a-z 0-9 - . _ ~}), so it
 * never needs percent-encoding and can never introduce path separators.</p>
 */
public final class Slugs {

    /** Maximum length of a generated segment. */
    public static final int MAX_LENGTH = 200;

    private Slugs() {
        // utility class
    }

    /**
     * Sanitises a slug.
     *
     * @param slug the raw slug, possibly percent-encoded; may be {@code null}
     * @return the sanitised segment, or empty when nothing usable remains
     */
    public static Optional<String> sanitize(final String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        String value = slug.trim();
        try {
            value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // not valid percent-encoding: use the raw value
        }
        final StringBuilder out = new StringBuilder(value.length());
        boolean lastWasDash = false;
        for (int i = 0; i < value.length() && out.length() < MAX_LENGTH; i++) {
            final char c = value.charAt(i);
            final boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append(c);
                lastWasDash = c == '-';
            } else if (!lastWasDash && out.length() > 0) {
                out.append('-');
                lastWasDash = true;
            }
        }
        String result = out.toString();
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        // "." and ".." are dot-segments, never valid names
        if (result.isEmpty() || result.chars().allMatch(c -> c == '.')) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

}
