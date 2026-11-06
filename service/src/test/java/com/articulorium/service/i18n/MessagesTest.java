package com.articulorium.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

import com.articulorium.i18n.I18nAutoConfiguration;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.LocaleManifest;
import com.articulorium.i18n.MessageBundles;

/**
 * The service's message bundle is translated for every shipped locale.
 */
class MessagesTest {

    @Test
    void everyShippedLocaleHasEveryText() {
        final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
        assertThat(MessageBundles.verify(I18nAutoConfiguration.MESSAGES, locales, Locale.ENGLISH)).isEmpty();
    }

}
