package com.articulorium.platform.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.i18n.I18nAutoConfiguration;
import com.articulorium.i18n.LocaleDescriptor;
import com.articulorium.i18n.LocaleManifest;
import com.articulorium.i18n.MessageBundles;
import com.articulorium.i18n.Messages;
import com.articulorium.i18n.SupportedLocales;
import com.articulorium.platform.ldp.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * The platform's message bundle is complete for every shipped locale, and coded messages match
 * the default-locale text kept for logs.
 */
class PlatformMessagesTest {

    @Test
    void everyShippedLocaleHasEveryText() {
        final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
        assertThat(MessageBundles.verify(I18nAutoConfiguration.MESSAGES, locales, Locale.ENGLISH)).isEmpty();
    }

    @Test
    void translatedMessagesMatchTheLoggedText() {
        final Messages messages = Messages.of(new SupportedLocales(LocaleManifest.load(), "en"));
        final ResourceNotFoundException missing = new ResourceNotFoundException("books/x");
        assertThat(messages.get(Locale.ENGLISH, missing.getMessageCode(), missing.getMessageArguments()))
            .isEqualTo(missing.getMessage());
        assertThat(messages.get(Locale.GERMAN, missing.getMessageCode(), missing.getMessageArguments()))
            .contains("/books/x").isNotEqualTo(missing.getMessage());
    }

}
