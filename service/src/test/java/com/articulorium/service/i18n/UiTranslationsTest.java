package com.articulorium.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.SupportedLocales;

/**
 * Unit tests for {@link UiTranslations}.
 */
class UiTranslationsTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final SupportedLocales locales = new SupportedLocales(List.of(LocaleDescriptor.of("en"),
        LocaleDescriptor.of("de"), new LocaleDescriptor("ar", "العربية", "rtl")), "en");

    private final UiTranslations translations = new UiTranslations(new DefaultResourceLoader(),
        "classpath:/test-i18n/", locales, json, "articulorium-locale");

    @Test
    void mergesTheTranslationOverTheDefaultTexts() {
        final JsonNode state = json.readTree(translations.state("de-AT").orElseThrow().json());
        assertThat(state.get("locale").asString()).isEqualTo("de");
        assertThat(state.get("defaultLocale").asString()).isEqualTo("en");
        assertThat(state.at("/messages/nav/browse").asString()).isEqualTo("Durchsuchen");
        // blank and missing translations keep the default text
        assertThat(state.at("/messages/nav/search").asString()).isEqualTo("Search");
        assertThat(state.at("/messages/only/english").asString()).isEqualTo("English only");
        assertThat(state.get("locales")).hasSize(3);
    }

    @Test
    void describesTheWritingDirection() {
        final JsonNode state = json.readTree(translations.state("ar").orElseThrow().json());
        assertThat(state.get("direction").asString()).isEqualTo("rtl");
        // no ar.json: every text in the default locale
        assertThat(state.at("/messages/nav/browse").asString()).isEqualTo("Browse");
    }

    @Test
    void refusesLocalesThatAreNotEnabled() {
        assertThat(translations.state("ja")).isEmpty();
    }

    @Test
    void tagsEachStateAndEscapesItForScriptElements() {
        final UiTranslations.State english = translations.state(locales.defaultLocale());
        assertThat(english.etag()).startsWith("\"").isNotEqualTo(translations.state("de").orElseThrow().etag());
        assertThat(new UiTranslations.State(locales.defaultLocale(), "{\"x\":\"</script><!--\"}", "\"1\"")
            .inlineJson()).doesNotContain("<").contains("\\u003c/script>");
    }

}
