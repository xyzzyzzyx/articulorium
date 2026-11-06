package com.articulorium.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the manifest of shipped locales, {@value #RESOURCE}.
 *
 * <p>Each non-comment line is {@code <tag> = <name>[, rtl]}. The manifest is the single list of
 * locales Articulorium ships translations for; deployments narrow it with
 * {@code articulorium.i18n.locales}.</p>
 */
public final class LocaleManifest {

    /** Class path location of the manifest. */
    public static final String RESOURCE = "articulorium/locales.properties";

    private LocaleManifest() {
    }

    /**
     * Loads the shipped locales from the class path.
     *
     * @return the locales, in manifest order
     * @throws IllegalStateException when the manifest is missing or empty
     */
    public static List<LocaleDescriptor> load() {
        try (InputStream in = LocaleManifest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Locale manifest " + RESOURCE + " is missing");
            }
            final List<LocaleDescriptor> locales = parse(new String(in.readAllBytes(),
                StandardCharsets.UTF_8));
            if (locales.isEmpty()) {
                throw new IllegalStateException("Locale manifest " + RESOURCE + " lists no locale");
            }
            return locales;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    /**
     * Parses manifest text.
     *
     * @param text the manifest
     * @return the locales, in order
     * @throws IllegalArgumentException for a malformed line
     */
    static List<LocaleDescriptor> parse(final String text) {
        final List<LocaleDescriptor> locales = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final int equals = trimmed.indexOf('=');
                if (equals < 1) {
                    throw new IllegalArgumentException("Malformed locale manifest line: " + line);
                }
                final String tag = trimmed.substring(0, equals).strip();
                final String value = trimmed.substring(equals + 1).strip();
                final int comma = value.lastIndexOf(',');
                final String direction = comma < 0 ? null : value.substring(comma + 1).strip();
                final boolean hasDirection = LocaleDescriptor.RTL.equalsIgnoreCase(direction)
                    || LocaleDescriptor.LTR.equalsIgnoreCase(direction);
                locales.add(new LocaleDescriptor(tag,
                    hasDirection ? value.substring(0, comma).strip() : value,
                    hasDirection ? direction : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return locales;
    }

}
