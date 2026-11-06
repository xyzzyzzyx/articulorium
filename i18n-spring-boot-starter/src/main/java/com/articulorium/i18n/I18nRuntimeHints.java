package com.articulorium.i18n;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Includes the locale manifest and every message bundle in GraalVM native images.
 */
public class I18nRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
        hints.resources().registerPattern(LocaleManifest.RESOURCE);
        hints.resources().registerPattern("i18n/*.properties");
    }

}
