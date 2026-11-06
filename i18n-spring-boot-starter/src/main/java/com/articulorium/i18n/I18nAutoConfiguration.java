package com.articulorium.i18n;

import java.util.List;
import java.util.Locale;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * Auto-configuration of locale support for Articulorium services.
 *
 * <ul>
 *   <li>{@link SupportedLocales}: the shipped locales ({@value LocaleManifest#RESOURCE}),
 *       narrowed by {@code articulorium.i18n.locales}, and the default locale;</li>
 *   <li>a {@link MessageSource} over the service's {@code i18n/messages} bundle and the shared
 *       {@code i18n/common} bundle, falling back to the default locale (never to the server's
 *       system locale), and {@link Messages} to use it;</li>
 *   <li>in Spring MVC applications, the {@link RequestLocaleResolver} as
 *       {@code localeResolver};</li>
 *   <li>an {@link AcceptLanguageInterceptor} for HTTP clients calling other services;</li>
 *   <li>a start-up check logging translations missing from the bundles.</li>
 * </ul>
 *
 * <p>Set {@code articulorium.i18n.enabled=false} to turn it off.</p>
 */
@AutoConfiguration(beforeName = {
    "org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration",
    "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration",
    "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration" })
@ConditionalOnBooleanProperty(name = "articulorium.i18n.enabled", matchIfMissing = true)
@EnableConfigurationProperties(I18nProperties.class)
@ImportRuntimeHints(I18nRuntimeHints.class)
public class I18nAutoConfiguration {

    /** Base name of each service's own message bundle. */
    public static final String MESSAGES = "i18n/messages";

    /** Base name of the message bundle shared by every service (this starter). */
    public static final String COMMON = "i18n/common";

    private static final Log LOG = LogFactory.getLog(I18nAutoConfiguration.class);

    /**
     * The enabled locales.
     *
     * @param properties the settings
     * @return the locales
     */
    @Bean
    @ConditionalOnMissingBean
    public SupportedLocales supportedLocales(final I18nProperties properties) {
        final SupportedLocales locales = SupportedLocales.of(properties, LocaleManifest.load());
        LOG.info("Locales " + locales.tags() + ", default " + locales.defaultLocale().tag());
        return locales;
    }

    /**
     * The message bundles.
     *
     * @param locales the enabled locales
     * @return the message source
     */
    @Bean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    public MessageSource messageSource(final SupportedLocales locales) {
        return Messages.bundles(locales);
    }

    /**
     * Translated texts in the request's locale.
     *
     * @param messageSource the message bundles
     * @param locales       the enabled locales
     * @return the lookup
     */
    @Bean
    @ConditionalOnMissingBean
    public Messages messages(final MessageSource messageSource, final SupportedLocales locales) {
        return new Messages(messageSource, locales);
    }

    /**
     * Passes the request's locale to called services.
     *
     * @return the interceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public AcceptLanguageInterceptor acceptLanguageInterceptor() {
        return new AcceptLanguageInterceptor();
    }

    /**
     * Logs translations missing from the service's and the shared bundles.
     *
     * @param locales the enabled locales
     * @return the check, run once every singleton exists
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "articulorium.i18n.verify-bundles", matchIfMissing = true)
    public SmartInitializingSingleton messageBundleCheck(final SupportedLocales locales) {
        return () -> {
            final List<Locale> tags = locales.all().stream().map(LocaleDescriptor::locale).toList();
            final Locale fallback = locales.defaultLocale().locale();
            for (final String basename : List.of(MESSAGES, COMMON)) {
                MessageBundles.verify(basename, tags, fallback).forEach(problem -> LOG.warn(
                    "Incomplete translation (the default locale is used instead): " + problem));
            }
        };
    }

    /**
     * Spring MVC support, configured only in servlet applications.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.LocaleResolver")
    static class ServletLocaleConfiguration {

        /**
         * Resolves each request's locale.
         *
         * @param locales    the enabled locales
         * @param properties the settings
         * @return the resolver, under the name the dispatcher servlet looks for
         */
        @Bean(name = "localeResolver")
        @ConditionalOnMissingBean(name = "localeResolver")
        RequestLocaleResolver localeResolver(final SupportedLocales locales,
                final I18nProperties properties) {
            return new RequestLocaleResolver(locales, properties);
        }

    }

}
