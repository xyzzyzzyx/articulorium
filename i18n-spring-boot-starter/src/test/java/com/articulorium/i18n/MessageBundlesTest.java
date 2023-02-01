package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link MessageBundles}, and the completeness of the shared {@code i18n/common} bundle.
 */
class MessageBundlesTest {

    @Test
    void acceptsACompleteTranslation() {
        assertThat(MessageBundles.verify("i18n/test/sample", List.of(Locale.ENGLISH, Locale.GERMAN),
            Locale.ENGLISH)).isEmpty();
    }

    @Test
    void reportsMissingFilesKeysArgumentsAndQuotes() {
        assertThat(MessageBundles.verify("i18n/test/sample", List.of(Locale.FRENCH, Locale.ITALIAN),
            Locale.ENGLISH)).containsExactlyInAnyOrder(
                "i18n/test/sample_fr.properties lacks farewell",
                "i18n/test/sample_fr.properties greeting uses arguments [] instead of [0]",
                "i18n/test/sample_fr.properties has unknown key extra",
                "i18n/test/sample_fr.properties quote has a single apostrophe; write '' instead",
                "i18n/test/sample_it.properties is missing");
    }

    @Test
    void ignoresAbsentBundles() {
        assertThat(MessageBundles.verify("i18n/none", List.of(Locale.FRENCH), Locale.ENGLISH)).isEmpty();
    }

    @Test
    void sharedBundleIsTranslatedForEveryShippedLocale() {
        final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
        assertThat(MessageBundles.verify(I18nAutoConfiguration.COMMON, locales, Locale.ENGLISH)).isEmpty();
    }

}
