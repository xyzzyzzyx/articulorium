package com.articulorium.transform.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import com.articulorium.i18n.I18nAutoConfiguration;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.LocaleManifest;
import com.articulorium.i18n.MessageBundles;

import org.junit.jupiter.api.Test;

/**
 * The transform service's message bundle is translated for every shipped locale.
 */
class TransformMessagesTest {

    @Test
    void everyShippedLocaleHasEveryText() {
        final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
        assertThat(MessageBundles.verify(I18nAutoConfiguration.MESSAGES, locales, Locale.ENGLISH)).isEmpty();
    }

}
