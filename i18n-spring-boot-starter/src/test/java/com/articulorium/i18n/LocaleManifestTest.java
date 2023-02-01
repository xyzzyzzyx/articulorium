package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link LocaleManifest} and {@link LocaleDescriptor}.
 */
class LocaleManifestTest {

    @Test
    void parsesTagsNamesAndDirections() {
        final List<LocaleDescriptor> locales = LocaleManifest.parse("""
            # comment

            en = English
            pt_br = Português (Brasil)
            ar = العربية, rtl
            """);
        assertThat(locales).containsExactly(
            new LocaleDescriptor("en", "English", "ltr"),
            new LocaleDescriptor("pt-BR", "Português (Brasil)", "ltr"),
            new LocaleDescriptor("ar", "العربية", "rtl"));
    }

    @Test
    void keepsCommasThatAreNotDirections() {
        assertThat(LocaleManifest.parse("x-test = One, two").get(0).name()).isEqualTo("One, two");
    }

    @Test
    void rejectsMalformedLines() {
        assertThatThrownBy(() -> LocaleManifest.parse("English"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocaleManifest.parse("not a tag! = Nope"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesNameAndDirectionOfUnlistedLocales() {
        final LocaleDescriptor hebrew = LocaleDescriptor.of("he");
        assertThat(hebrew.direction()).isEqualTo(LocaleDescriptor.RTL);
        assertThat(hebrew.name()).isNotBlank();
        assertThat(LocaleDescriptor.of("sv").direction()).isEqualTo(LocaleDescriptor.LTR);
    }

    @Test
    void shipsEnglishAndABroadSetOfLocales() {
        final List<String> tags = LocaleManifest.load().stream().map(LocaleDescriptor::tag).toList();
        assertThat(tags).contains("en", "de", "es", "fr", "ja", "zh-CN", "ar").hasSizeGreaterThan(9)
            .doesNotHaveDuplicates();
    }

}
