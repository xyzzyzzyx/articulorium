package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests of {@link RequestLocaleResolver}.
 */
class RequestLocaleResolverTest {

    private final RequestLocaleResolver resolver = new RequestLocaleResolver(new SupportedLocales(
        List.of(LocaleDescriptor.of("en"), LocaleDescriptor.of("de"), LocaleDescriptor.of("fr")), "en"),
        new I18nProperties());

    @Test
    void prefersTheParameterThenTheCookieThenTheHeader() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr");
        request.setCookies(new Cookie("articulorium-locale", "de"));
        request.setParameter("lang", "en");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);

        final MockHttpServletRequest withoutParameter = new MockHttpServletRequest();
        withoutParameter.addHeader("Accept-Language", "fr");
        withoutParameter.setCookies(new Cookie("articulorium-locale", "de"));
        assertThat(resolver.resolveLocale(withoutParameter)).isEqualTo(Locale.GERMAN);

        final MockHttpServletRequest headerOnly = new MockHttpServletRequest();
        headerOnly.addHeader("Accept-Language", "fr-CH, en;q=0.5");
        assertThat(resolver.resolveLocale(headerOnly)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void skipsUnsupportedValues() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("lang", "xx");
        request.setCookies(new Cookie("articulorium-locale", "<script>"));
        request.addHeader("Accept-Language", "sv");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void writesTheCookie() {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        resolver.setLocale(new MockHttpServletRequest(), response, Locale.GERMAN);
        final Cookie cookie = response.getCookie("articulorium-locale");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("de");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isPositive();
    }

}
