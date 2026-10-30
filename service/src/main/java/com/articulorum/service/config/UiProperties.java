package com.articulorum.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings handed to the user interface ({@code articulorum.ui.*}).
 *
 * @param platformUrl URL of the Linked Data Platform root as seen by browsers, relative to the
 *                    user interface (for example {@code ../platform/}: the UI is served under
 *                    {@code /ui/} by the gateway, the platform under {@code /platform/}) or
 *                    absolute
 * @param title       title shown in the user interface
 */
@ConfigurationProperties(prefix = "articulorum.ui")
public record UiProperties(String platformUrl, String title) {

    /** Platform URL used when none is configured: the gateway route, relative to {@code /ui/}. */
    public static final String DEFAULT_PLATFORM_URL = "../platform/";

    /** Title used when none is configured. */
    public static final String DEFAULT_TITLE = "Articulorum";

    /**
     * Creates the settings, applying defaults.
     *
     * @param platformUrl URL of the platform root, may be {@code null}
     * @param title       title shown in the user interface, may be {@code null}
     */
    public UiProperties {
        platformUrl = platformUrl == null || platformUrl.isBlank()
            ? DEFAULT_PLATFORM_URL
            : platformUrl.trim();
        title = title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
    }

}
