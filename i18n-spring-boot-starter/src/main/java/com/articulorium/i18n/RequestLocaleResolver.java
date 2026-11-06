package com.articulorium.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Resolves the locale of a request among the enabled locales.
 *
 * <ol>
 *   <li>the query parameter ({@code ?lang=de}), for links and harvesters;</li>
 *   <li>the cookie the user interface writes when the user picks a language;</li>
 *   <li>the {@code Accept-Language} header (sent by browsers, and by Articulorium services to
 *       each other);</li>
 *   <li>the default locale.</li>
 * </ol>
 *
 * <p>Values that are malformed or not enabled are skipped, so a request always gets an enabled
 * locale.</p>
 */
public class RequestLocaleResolver implements LocaleResolver {

    /** Request attribute caching the resolved locale. */
    static final String ATTRIBUTE = RequestLocaleResolver.class.getName() + ".LOCALE";

    /** Lifetime of the locale cookie: one year. */
    static final int COOKIE_MAX_AGE = 365 * 24 * 60 * 60;

    private final SupportedLocales locales;

    private final I18nProperties properties;

    /**
     * Creates the resolver.
     *
     * @param locales    the enabled locales
     * @param properties the settings (cookie and parameter names)
     */
    public RequestLocaleResolver(final SupportedLocales locales, final I18nProperties properties) {
        this.locales = locales;
        this.properties = properties;
    }

    @Override
    public Locale resolveLocale(final HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof Locale cached) {
            return cached;
        }
        final Locale locale = resolve(request).orElse(locales.defaultLocale()).locale();
        request.setAttribute(ATTRIBUTE, locale);
        return locale;
    }

    /**
     * Remembers a locale in the cookie (or forgets it with {@code null}).
     *
     * @param request  the request
     * @param response the response
     * @param locale   the locale, {@code null} to forget the choice
     */
    @Override
    public void setLocale(final HttpServletRequest request, final HttpServletResponse response,
            final Locale locale) {
        final Cookie cookie = new Cookie(properties.getCookieName(),
            locale == null ? "" : locales.describe(locale).tag());
        cookie.setPath("/");
        cookie.setMaxAge(locale == null ? 0 : COOKIE_MAX_AGE);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        request.removeAttribute(ATTRIBUTE);
    }

    private Optional<LocaleDescriptor> resolve(final HttpServletRequest request) {
        final Optional<LocaleDescriptor> parameter =
            locales.match(request.getParameter(properties.getParameterName()));
        if (parameter.isPresent()) {
            return parameter;
        }
        final Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (final Cookie cookie : cookies) {
                if (properties.getCookieName().equals(cookie.getName())) {
                    final Optional<LocaleDescriptor> chosen = locales.match(cookie.getValue());
                    if (chosen.isPresent()) {
                        return chosen;
                    }
                }
            }
        }
        return locales.negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

}
