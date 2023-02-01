package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Tests of locale negotiation.
 */
class SupportedLocalesTest {

    private final SupportedLocales locales = new SupportedLocales(List.of(LocaleDescriptor.of("en"),
        LocaleDescriptor.of("de"), LocaleDescriptor.of("pt-BR"), LocaleDescriptor.of("zh-CN")), "en");

    @Test
    void matchesExactTagsCaseInsensitively() {
        assertThat(locales.match("pt-br")).map(LocaleDescriptor::tag).contains("pt-BR");
        assertThat(locales.match("zh_CN")).map(LocaleDescriptor::tag).contains("zh-CN");
    }

    @Test
    void matchesTheSameLanguageInAnotherRegion() {
        assertThat(locales.match("de-AT")).map(LocaleDescriptor::tag).contains("de");
        assertThat(locales.match("pt-PT")).map(LocaleDescriptor::tag).contains("pt-BR");
        assertThat(locales.match("zh")).map(LocaleDescriptor::tag).contains("zh-CN");
    }

    @Test
    void ignoresUnknownAndMalformedTags() {
        assertThat(locales.match("sv")).isEmpty();
        assertThat(locales.match("!!")).isEmpty();
        assertThat(locales.match(null)).isEmpty();
    }

    @Test
    void negotiatesAcceptLanguageByWeight() {
        assertThat(locales.negotiate("sv, de;q=0.5, en;q=0.8")).map(LocaleDescriptor::tag).contains("en");
        assertThat(locales.negotiate("fr-CA, pt;q=0.9")).map(LocaleDescriptor::tag).contains("pt-BR");
        assertThat(locales.negotiate("sv, *;q=0.1")).map(LocaleDescriptor::tag).contains("en");
        assertThat(locales.negotiate("de;q=0, sv")).isEmpty();
        assertThat(locales.negotiate("not a header;;")).isEmpty();
    }

    @Test
    void resolvesToTheDefaultLocale() {
        assertThat(locales.resolve("sv")).isEqualTo(Locale.ENGLISH);
        assertThat(locales.resolve("de")).isEqualTo(Locale.GERMAN);
        assertThat(locales.describe(Locale.JAPANESE).tag()).isEqualTo("en");
    }

    @Test
    void narrowsTheManifestWithSettings() {
        final I18nProperties properties = new I18nProperties();
        properties.setLocales(List.of("de", "en", "sv"));
        properties.setDefaultLocale("de");
        final SupportedLocales narrowed = SupportedLocales.of(properties,
            List.of(new LocaleDescriptor("en", "English", null), new LocaleDescriptor("de", "Deutsch", null)));
        assertThat(narrowed.tags()).containsExactly("de", "en", "sv");
        assertThat(narrowed.all().get(0).name()).isEqualTo("Deutsch");
        assertThat(narrowed.defaultLocale().tag()).isEqualTo("de");
    }

    @Test
    void requiresAnEnabledDefaultLocale() {
        assertThatThrownBy(() -> new SupportedLocales(List.of(LocaleDescriptor.of("en")), "de"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("default-locale");
    }

}
