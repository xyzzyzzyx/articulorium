package com.articulorium.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.Messages;
import com.articulorium.i18n.SupportedLocales;
import com.articulorium.service.i18n.UiTranslations;

/**
 * Unit tests for {@link IndexPage}: the page is rendered in the request's locale.
 */
class IndexPageTest {

    private final SupportedLocales locales = new SupportedLocales(List.of(LocaleDescriptor.of("en"),
        LocaleDescriptor.of("de"), new LocaleDescriptor("ar", "العربية", "rtl")), "en");

    private final IndexPage page = new IndexPage(new Messages(new StaticMessageSource(), locales),
        new UiTranslations(new DefaultResourceLoader(), "classpath:/test-i18n/", locales,
            JsonMapper.builder().build(), "articulorium-locale"));

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private ExtendedModelMap render(final Locale locale, final MockHttpServletResponse response) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ui/browse");
        request.setContextPath("/ui");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        LocaleContextHolder.setLocale(locale);
        final ExtendedModelMap model = new ExtendedModelMap();
        assertThat(page.render(model, response)).isEqualTo(IndexPage.VIEW);
        return model;
    }

    @Test
    void rendersTheDocumentInTheRequestLocale() {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final ExtendedModelMap model = render(Locale.GERMAN, response);
        assertThat(model.get("base")).isEqualTo("/ui/");
        assertThat(model.get("lang")).isEqualTo("de");
        assertThat(model.get("dir")).isEqualTo("ltr");
        assertThat((String) model.get("i18n")).contains("\"locale\":\"de\"").contains("Durchsuchen");
        assertThat(response.getHeader("Content-Language")).isEqualTo("de");
        assertThat(response.getHeader("Vary")).contains("Accept-Language").contains("Cookie");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void writesRightToLeftLocalesRightToLeft() {
        final ExtendedModelMap model = render(Locale.forLanguageTag("ar"), new MockHttpServletResponse());
        assertThat(model.get("lang")).isEqualTo("ar");
        assertThat(model.get("dir")).isEqualTo("rtl");
    }

    @Test
    void fallsBackToTheDefaultLocale() {
        final ExtendedModelMap model = render(Locale.JAPANESE, new MockHttpServletResponse());
        assertThat(model.get("lang")).isEqualTo("en");
    }

}
