package com.articulorium.oai.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.articulorium.domain.rdf.ResourcePaths;

/**
 * Maps the paths of communities and collections (relative to the communities container) to
 * hierarchical setSpecs and back: one setSpec component per path segment, joined with {@code :},
 * so {@code arts/paintings} is {@code arts:paintings}, a set inside {@code arts}.
 *
 * <p>Letters, digits and {@code - _ . ~} are kept; every other character (including {@code !},
 * the escape character) is written as {@code !} followed by two hexadecimal digits per UTF-8
 * byte, so any path segment has exactly one legal setSpec component.</p>
 */
public final class SetSpecs {

    /** A setSpec as defined by the OAI-PMH schema. */
    private static final Pattern SPEC = Pattern.compile("[A-Za-z0-9\\-_.!~*'()]+(:[A-Za-z0-9\\-_.!~*'()]+)*");

    private static final char ESCAPE = '!';

    private static final String HEX = "0123456789ABCDEF";

    private SetSpecs() {
        // utility class
    }

    /**
     * Converts a path relative to the communities container into a setSpec.
     *
     * @param relativePath for example {@code arts/paintings}
     * @return for example {@code arts:paintings}
     */
    public static String encode(final String relativePath) {
        final List<String> components = new ArrayList<>();
        for (final String segment : ResourcePaths.normalize(relativePath).split("/")) {
            final StringBuilder component = new StringBuilder();
            for (final byte b : segment.getBytes(StandardCharsets.UTF_8)) {
                final char c = (char) (b & 0xff);
                if (c < 0x80 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~')) {
                    component.append(c);
                } else {
                    component.append(ESCAPE).append(HEX.charAt((b >> 4) & 0xf)).append(HEX.charAt(b & 0xf));
                }
            }
            components.add(component.toString());
        }
        return String.join(":", components);
    }

    /**
     * Converts a setSpec into the path relative to the communities container.
     *
     * @param spec the setSpec
     * @return the relative path
     * @throws OaiException {@link OaiError#BAD_ARGUMENT} when the value is not a legal setSpec,
     *                      {@link OaiError#NO_RECORDS_MATCH} when it is legal but not one this
     *                      repository could have minted
     */
    public static String decode(final String spec) {
        if (spec == null || !SPEC.matcher(spec).matches()) {
            throw new OaiException(OaiError.BAD_ARGUMENT, "Not a legal setSpec: " + spec);
        }
        final List<String> segments = new ArrayList<>();
        for (final String component : spec.split(":")) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < component.length(); i++) {
                final char c = component.charAt(i);
                if (c == ESCAPE) {
                    if (i + 2 >= component.length()) {
                        throw unknown(spec);
                    }
                    final int high = HEX.indexOf(component.charAt(i + 1));
                    final int low = HEX.indexOf(component.charAt(i + 2));
                    if (high < 0 || low < 0) {
                        throw unknown(spec);
                    }
                    bytes.write(high << 4 | low);
                    i += 2;
                } else if (c == '*' || c == '\'' || c == '(' || c == ')') {
                    // legal in a setSpec, but encode() never leaves them unescaped
                    throw unknown(spec);
                } else {
                    bytes.write(c);
                }
            }
            final String segment = bytes.toString(StandardCharsets.UTF_8);
            if (segment.isEmpty() || segment.contains("/")) {
                throw unknown(spec);
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static OaiException unknown(final String spec) {
        return new OaiException(OaiError.NO_RECORDS_MATCH, "No such set in this repository: " + spec);
    }

}
