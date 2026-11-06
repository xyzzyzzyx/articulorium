package com.articulorium.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Looks up translated texts in the current request's locale.
 *
 * <p>Codes are looked up in the service's {@code i18n/messages} bundle, then in the shared
 * {@code i18n/common} bundle; a text missing from the locale's bundle comes from the default
 * locale. A code missing everywhere is a programming error the bundle tests catch; at run time it
 * yields the code itself rather than failing the request.</p>
 */
public class Messages {

    private final MessageSource source;

    private final SupportedLocales locales;

    /**
     * Creates the lookup.
     *
     * @param source  the message bundles
     * @param locales the enabled locales
     */
    public Messages(final MessageSource source, final SupportedLocales locales) {
        this.source = source;
        this.locales = locales;
    }

    /**
     * Creates the lookup over the standard bundles, outside a Spring context (tests, tools).
     *
     * @param locales the enabled locales
     * @return the lookup
     */
    public static Messages of(final SupportedLocales locales) {
        return new Messages(bundles(locales), locales);
    }

    /**
     * The standard message bundles: the service's {@code i18n/messages}, then the shared
     * {@code i18n/common}, falling back to the default locale (never to the system locale).
     *
     * @param locales the enabled locales
     * @return the message source
     */
    public static MessageSource bundles(final SupportedLocales locales) {
        final ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(I18nAutoConfiguration.MESSAGES, I18nAutoConfiguration.COMMON);
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(locales.defaultLocale().locale());
        // every text is a MessageFormat pattern, with or without arguments: an apostrophe is
        // always written '' (MessageBundles rejects a single one)
        source.setAlwaysUseMessageFormat(true);
        return source;
    }

    /**
     * A text in the current locale.
     *
     * @param code      the message code
     * @param arguments {@link java.text.MessageFormat} arguments
     * @return the text
     */
    public String get(final String code, final Object... arguments) {
        return get(locale(), code, arguments);
    }

    /**
     * A text in a given locale.
     *
     * @param locale    the locale
     * @param code      the message code
     * @param arguments {@link java.text.MessageFormat} arguments
     * @return the text
     */
    public String get(final Locale locale, final String code, final Object... arguments) {
        try {
            return source.getMessage(code, arguments, locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }

    /**
     * The locale of the current request (an enabled locale), or the default locale outside
     * requests.
     *
     * @return the locale
     */
    public Locale locale() {
        return locales.describe(LocaleContextHolder.getLocaleContext() == null ? null
            : LocaleContextHolder.getLocale()).locale();
    }

    /**
     * The default locale, the language of texts that have no translation.
     *
     * @return the descriptor
     */
    public LocaleDescriptor defaultLocale() {
        return locales.defaultLocale();
    }

    /**
     * A summary of an HTTP error status in the current locale ({@code error.status.<code>} of
     * the shared bundle).
     *
     * @param status the status code, for example {@code 404}
     * @return the summary, a generic one for statuses without their own
     */
    public String status(final int status) {
        final String code = "error.status." + status;
        final String text = get(code);
        return code.equals(text) ? get("error.status.other") : text;
    }

    /**
     * The descriptor of the current locale (tag, name, writing direction).
     *
     * @return the descriptor
     */
    public LocaleDescriptor descriptor() {
        return locales.describe(locale());
    }

}
