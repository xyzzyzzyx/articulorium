package com.articulorium.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The locales a deployment is used in, and the negotiation of a request's locale among them.
 *
 * <p>Matching is the same everywhere (server pages, error messages, the user interface): an
 * exact tag match wins ({@code pt-BR}), then a locale of the same language ({@code pt-PT} asks
 * for {@code pt-BR} when only that is enabled, {@code zh} gets {@code zh-CN}). Nothing matching
 * means the default locale.</p>
 */
public final class SupportedLocales {

    private final List<LocaleDescriptor> locales;

    private final LocaleDescriptor defaultLocale;

    /**
     * Creates the supported locales.
     *
     * @param locales       the enabled locales, in menu order; not empty
     * @param defaultLocale the tag of the default locale; must be enabled
     * @throws IllegalArgumentException when the default locale is not enabled
     */
    public SupportedLocales(final List<LocaleDescriptor> locales, final String defaultLocale) {
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("At least one locale must be enabled");
        }
        final Map<String, LocaleDescriptor> unique = new LinkedHashMap<>();
        locales.forEach(locale ->
            unique.putIfAbsent(locale.tag().toLowerCase(Locale.ROOT), locale));
        this.locales = List.copyOf(unique.values());
        final String tag = LocaleDescriptor.parse(defaultLocale).toLanguageTag();
        this.defaultLocale = exact(tag).orElseThrow(() -> new IllegalArgumentException(
            "articulorium.i18n.default-locale " + tag + " is not among the enabled locales "
                + tags()));
    }

    /**
     * Builds the supported locales from settings and the shipped manifest.
     *
     * @param properties the settings
     * @param manifest   the shipped locales
     * @return the supported locales
     */
    public static SupportedLocales of(final I18nProperties properties,
            final List<LocaleDescriptor> manifest) {
        if (properties.getLocales().isEmpty()) {
            return new SupportedLocales(manifest, properties.getDefaultLocale());
        }
        final List<LocaleDescriptor> enabled = new ArrayList<>();
        for (final String tag : properties.getLocales()) {
            final String normalized = LocaleDescriptor.parse(tag).toLanguageTag();
            enabled.add(manifest.stream()
                .filter(locale -> locale.tag().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseGet(() -> LocaleDescriptor.of(normalized)));
        }
        return new SupportedLocales(enabled, properties.getDefaultLocale());
    }

    /**
     * The enabled locales, in menu order.
     *
     * @return the locales
     */
    public List<LocaleDescriptor> all() {
        return locales;
    }

    /**
     * The enabled tags, in menu order.
     *
     * @return for example {@code [en, de, pt-BR]}
     */
    public List<String> tags() {
        return locales.stream().map(LocaleDescriptor::tag).toList();
    }

    /**
     * The default locale.
     *
     * @return the descriptor
     */
    public LocaleDescriptor defaultLocale() {
        return defaultLocale;
    }

    /**
     * Finds the enabled locale best matching one tag.
     *
     * @param tag a language tag such as {@code de-AT}, may be {@code null}
     * @return the match, empty when none matches or the tag is malformed
     */
    public Optional<LocaleDescriptor> match(final String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        final Locale requested;
        try {
            requested = LocaleDescriptor.parse(tag);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return exact(requested.toLanguageTag()).or(() -> sameLanguage(requested));
    }

    /**
     * Negotiates an {@code Accept-Language} header.
     *
     * @param acceptLanguage the header, may be {@code null}
     * @return the best enabled locale, empty when none is acceptable
     */
    public Optional<LocaleDescriptor> negotiate(final String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Optional.empty();
        }
        final List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // ranges come sorted by weight: the first one with a match decides
        for (final Locale.LanguageRange range : ranges) {
            if (range.getWeight() <= 0) {
                continue;
            }
            if ("*".equals(range.getRange())) {
                return Optional.of(defaultLocale);
            }
            final Optional<LocaleDescriptor> match = match(range.getRange());
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a tag to an enabled locale, falling back to the default locale.
     *
     * @param tag a language tag, may be {@code null}
     * @return the locale
     */
    public Locale resolve(final String tag) {
        return match(tag).orElse(defaultLocale).locale();
    }

    /**
     * The descriptor of an enabled locale.
     *
     * @param locale the locale
     * @return the descriptor, or the default locale's when the locale is not enabled
     */
    public LocaleDescriptor describe(final Locale locale) {
        return locale == null ? defaultLocale : match(locale.toLanguageTag()).orElse(defaultLocale);
    }

    private Optional<LocaleDescriptor> exact(final String tag) {
        return locales.stream().filter(locale -> locale.tag().equalsIgnoreCase(tag)).findFirst();
    }

    private Optional<LocaleDescriptor> sameLanguage(final Locale requested) {
        final List<LocaleDescriptor> candidates = locales.stream()
            .filter(locale -> locale.locale().getLanguage().equals(requested.getLanguage()))
            .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // prefer the plain language ("de") over a regional variant, then the first in menu order
        return candidates.stream()
            .filter(locale -> locale.locale().getCountry().isEmpty())
            .findFirst()
            .or(() -> Optional.of(candidates.get(0)));
    }

}
