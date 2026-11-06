package com.articulorium.i18n;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Locale settings of a service ({@code articulorium.i18n.*}).
 *
 * <p>Every service of a deployment should use the same values (Consul key/value configuration
 * shares them): the user interface offers the locales of the {@code service} module and the other
 * services answer in the locale its requests carry.</p>
 */
@ConfigurationProperties(prefix = "articulorium.i18n")
public class I18nProperties {

    /** Default name of the cookie remembering the chosen locale. */
    public static final String DEFAULT_COOKIE_NAME = "articulorium-locale";

    /** Default name of the query parameter choosing a locale for one request. */
    public static final String DEFAULT_PARAMETER_NAME = "lang";

    /**
     * Locale used when a request asks for none of the enabled locales, and for every text missing
     * from a translation (a BCP 47 tag; must be enabled).
     */
    private String defaultLocale = "en";

    /**
     * Enabled locales as BCP 47 tags, in menu order. Empty (the default) enables every locale of
     * the manifest {@value LocaleManifest#RESOURCE}. A tag missing from the manifest is accepted;
     * texts without a translation then fall back to the default locale.
     */
    private List<String> locales = new ArrayList<>();

    /** Name of the cookie remembering the chosen locale (written by the user interface). */
    private String cookieName = DEFAULT_COOKIE_NAME;

    /** Name of the query parameter choosing the locale of one request ({@code ?lang=de}). */
    private String parameterName = DEFAULT_PARAMETER_NAME;

    /** Whether missing translations are logged at start-up. */
    private boolean verifyBundles = true;

    /**
     * The default locale.
     *
     * @return a BCP 47 tag
     */
    public String getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Sets the default locale.
     *
     * @param defaultLocale a BCP 47 tag
     */
    public void setDefaultLocale(final String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    /**
     * The enabled locales; empty for every shipped locale.
     *
     * @return BCP 47 tags
     */
    public List<String> getLocales() {
        return locales;
    }

    /**
     * Sets the enabled locales.
     *
     * @param locales BCP 47 tags, empty for every shipped locale
     */
    public void setLocales(final List<String> locales) {
        this.locales = locales == null ? new ArrayList<>() : new ArrayList<>(locales);
    }

    /**
     * The name of the locale cookie.
     *
     * @return the cookie name
     */
    public String getCookieName() {
        return cookieName;
    }

    /**
     * Sets the name of the locale cookie.
     *
     * @param cookieName the cookie name
     */
    public void setCookieName(final String cookieName) {
        this.cookieName = cookieName;
    }

    /**
     * The name of the locale query parameter.
     *
     * @return the parameter name
     */
    public String getParameterName() {
        return parameterName;
    }

    /**
     * Sets the name of the locale query parameter.
     *
     * @param parameterName the parameter name
     */
    public void setParameterName(final String parameterName) {
        this.parameterName = parameterName;
    }

    /**
     * Whether missing translations are logged at start-up.
     *
     * @return {@code true} to check the message bundles
     */
    public boolean isVerifyBundles() {
        return verifyBundles;
    }

    /**
     * Sets whether missing translations are logged at start-up.
     *
     * @param verifyBundles {@code true} to check the message bundles
     */
    public void setVerifyBundles(final boolean verifyBundles) {
        this.verifyBundles = verifyBundles;
    }

}
