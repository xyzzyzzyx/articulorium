package com.articulorium.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.MessageSource;

/**
 * Tests of {@link I18nAutoConfiguration} against real application contexts.
 */
class I18nAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(I18nAutoConfiguration.class));

    @Test
    void isRegisteredAsAnAutoConfiguration() {
        final List<String> candidates = ImportCandidates
            .load(AutoConfiguration.class, getClass().getClassLoader())
            .getCandidates();
        assertThat(candidates).contains(I18nAutoConfiguration.class.getName());
    }

    @Test
    void configuresLocalesMessagesAndTheResolver() {
        web.run(context -> {
            assertThat(context.getBean(SupportedLocales.class).tags()).contains("en", "de", "ar");
            assertThat(context.getBean("localeResolver")).isInstanceOf(RequestLocaleResolver.class);
            assertThat(context).hasSingleBean(AcceptLanguageInterceptor.class);
            final MessageSource messages = context.getBean(MessageSource.class);
            assertThat(messages.getMessage("error.status.404", null, Locale.GERMAN))
                .isNotEqualTo(messages.getMessage("error.status.404", null, Locale.ENGLISH));
            // a locale without bundle falls back to the default locale, not the system locale
            assertThat(messages.getMessage("error.status.404", null, Locale.forLanguageTag("sv")))
                .isEqualTo(messages.getMessage("error.status.404", null, Locale.ENGLISH));
        });
    }

    @Test
    void honoursSettings() {
        web.withPropertyValues("articulorium.i18n.locales=fr,de", "articulorium.i18n.default-locale=fr")
            .run(context -> {
                final SupportedLocales locales = context.getBean(SupportedLocales.class);
                assertThat(locales.tags()).containsExactly("fr", "de");
                assertThat(context.getBean(Messages.class).get(Locale.forLanguageTag("sv"), "error.status.404"))
                    .isEqualTo(context.getBean(Messages.class).get(Locale.FRENCH, "error.status.404"));
            });
    }

    @Test
    void failsOnADefaultLocaleThatIsNotEnabled() {
        web.withPropertyValues("articulorium.i18n.locales=fr", "articulorium.i18n.default-locale=en")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuresNoResolverOutsideServletApplications() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(I18nAutoConfiguration.class))
            .run(context -> {
                assertThat(context).hasSingleBean(SupportedLocales.class);
                assertThat(context).doesNotHaveBean("localeResolver");
            });
    }

    @Test
    void canBeTurnedOff() {
        web.withPropertyValues("articulorium.i18n.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(SupportedLocales.class));
    }

}
