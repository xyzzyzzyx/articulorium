package com.articulorium.service.config;

import com.articulorium.i18n.I18nProperties;
import com.articulorium.i18n.SupportedLocales;
import com.articulorium.service.i18n.UiTranslations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The user interface's translations, read from the {@code ui} module's build.
 */
@Configuration(proxyBeanMethods = false)
public class I18nConfiguration {

    /**
     * The translations.
     *
     * @param resources  loads the files
     * @param locales    the enabled locales ({@code articulorium.i18n.*})
     * @param properties the locale settings (cookie name)
     * @param json       JSON support
     * @return the translations
     */
    @Bean
    public UiTranslations uiTranslations(final ResourceLoader resources,
            final SupportedLocales locales, final I18nProperties properties,
            final JsonMapper json) {
        return new UiTranslations(resources, UiTranslations.DEFAULT_LOCATION, locales, json,
            properties.getCookieName());
    }

}
