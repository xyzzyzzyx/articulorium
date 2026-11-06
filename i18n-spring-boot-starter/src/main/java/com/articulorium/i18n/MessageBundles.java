package com.articulorium.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks message bundles for missing translations.
 *
 * <p>The base bundle ({@code i18n/messages.properties}) is the default (English) text and the
 * reference: every locale's bundle must exist, define exactly its keys, and use the same
 * {@link java.text.MessageFormat} arguments ({@code {0}}, {@code {1}}), and every text, being a
 * {@code MessageFormat} pattern, writes an apostrophe as {@code ''}. Each module's tests run
 * this over its bundle for every shipped locale; services also log the problems at start-up.</p>
 */
public final class MessageBundles {

    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)[^}]*}");

    private static final Pattern SINGLE_QUOTE = Pattern.compile("(?<!')'(?!')");

    private MessageBundles() {
    }

    /**
     * Lists the problems of a bundle.
     *
     * @param basename      the class path base name, for example {@code i18n/messages}
     * @param locales       the locales that need a translation
     * @param defaultLocale the locale of the base bundle, which needs no file of its own
     * @return one line per problem; empty when the bundle is complete (or does not exist)
     */
    public static List<String> verify(final String basename, final Collection<Locale> locales,
            final Locale defaultLocale) {
        final Optional<Properties> base = load(basename + ".properties");
        if (base.isEmpty()) {
            return List.of();
        }
        final List<String> problems = new ArrayList<>();
        quotes(basename + ".properties", base.get(), problems);
        for (final Locale locale : locales) {
            if (locale.equals(defaultLocale)) {
                continue;
            }
            final String file = basename + "_" + locale.toString() + ".properties";
            final Optional<Properties> translation = load(file);
            if (translation.isEmpty()) {
                problems.add(file + " is missing");
                continue;
            }
            compare(file, base.get(), translation.get(), problems);
            quotes(file, translation.get(), problems);
        }
        return problems;
    }

    /**
     * Lists the texts of one bundle file with a single apostrophe, which {@code MessageFormat}
     * would swallow.
     *
     * @param file       the file name, for messages
     * @param properties its texts
     * @param problems   where problems are added
     */
    private static void quotes(final String file, final Properties properties,
            final List<String> problems) {
        for (final String key : new TreeSet<>(properties.stringPropertyNames())) {
            if (SINGLE_QUOTE.matcher(properties.getProperty(key)).find()) {
                problems.add(file + " " + key + " has a single apostrophe; write '' instead");
            }
        }
    }

    private static void compare(final String file, final Properties base,
            final Properties translation, final List<String> problems) {
        for (final String key : new TreeSet<>(base.stringPropertyNames())) {
            final String text = translation.getProperty(key);
            if (text == null || text.isBlank()) {
                problems.add(file + " lacks " + key);
            } else if (!arguments(base.getProperty(key)).equals(arguments(text))) {
                problems.add(file + " " + key + " uses arguments " + arguments(text)
                    + " instead of " + arguments(base.getProperty(key)));
            }
        }
        for (final String key : new TreeSet<>(translation.stringPropertyNames())) {
            if (!base.containsKey(key)) {
                problems.add(file + " has unknown key " + key);
            }
        }
    }

    private static Set<String> arguments(final String text) {
        final Set<String> arguments = new TreeSet<>();
        final Matcher matcher = ARGUMENT.matcher(text);
        while (matcher.find()) {
            arguments.add(matcher.group(1));
        }
        return arguments;
    }

    private static Optional<Properties> load(final String resource) {
        try (InputStream in = MessageBundles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return Optional.of(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }

}
