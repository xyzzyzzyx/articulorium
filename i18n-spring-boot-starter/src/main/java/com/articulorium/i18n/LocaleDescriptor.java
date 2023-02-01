package com.articulorium.i18n;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Set;

/**
 * A locale the application can be used in.
 *
 * @param tag       the BCP 47 language tag, for example {@code pt-BR}
 * @param name      the name of the language in that language, for example
 *                  {@code Português (Brasil)}
 * @param direction the writing direction: {@code ltr} or {@code rtl}
 */
public record LocaleDescriptor(String tag, String name, String direction) {

    /** Left-to-right writing. */
    public static final String LTR = "ltr";

    /** Right-to-left writing. */
    public static final String RTL = "rtl";

    /** Languages written right to left, for locales configured without a manifest entry. */
    private static final Set<String> RTL_LANGUAGES = Set.of("ar", "dv", "fa", "he", "ku", "ps",
        "sd", "ug", "ur", "yi");

    /**
     * Creates a descriptor, normalizing the tag and the direction.
     *
     * @param tag       the language tag
     * @param name      the name of the language, may be {@code null} (derived from the tag)
     * @param direction {@code ltr} or {@code rtl}, may be {@code null} (derived from the tag)
     * @throws IllegalArgumentException when the tag is not a well-formed language tag
     */
    public LocaleDescriptor {
        final Locale locale = parse(tag);
        tag = locale.toLanguageTag();
        name = name == null || name.isBlank() ? displayName(locale) : name.trim();
        if (direction == null || direction.isBlank()) {
            direction = RTL_LANGUAGES.contains(locale.getLanguage()) ? RTL : LTR;
        } else {
            direction = direction.trim().toLowerCase(Locale.ROOT);
        }
        if (!LTR.equals(direction) && !RTL.equals(direction)) {
            throw new IllegalArgumentException("Direction of " + tag + " must be ltr or rtl, not "
                + direction);
        }
    }

    /**
     * Creates a descriptor for a tag, deriving its name and direction.
     *
     * @param tag the language tag
     * @return the descriptor
     */
    public static LocaleDescriptor of(final String tag) {
        return new LocaleDescriptor(tag, null, null);
    }

    /**
     * The locale.
     *
     * @return for example {@code pt_BR}
     */
    public Locale locale() {
        return Locale.forLanguageTag(tag);
    }

    /**
     * Parses a well-formed language tag.
     *
     * @param tag the tag, {@code _} is accepted for {@code -}
     * @return the locale
     * @throws IllegalArgumentException when the tag is blank or not well-formed
     */
    static Locale parse(final String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("A locale needs a language tag");
        }
        try {
            return new Locale.Builder().setLanguageTag(tag.trim().replace('_', '-')).build();
        } catch (IllformedLocaleException e) {
            throw new IllegalArgumentException("Not a BCP 47 language tag: " + tag, e);
        }
    }

    private static String displayName(final Locale locale) {
        final String name = locale.getDisplayName(locale);
        return name.isEmpty() ? locale.toLanguageTag()
            : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

}
