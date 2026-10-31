package com.articulorum.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Settings handed to the user interface ({@code articulorum.ui.*}).
 *
 * <p>Every URL is relative to the user interface (served under {@code /ui/} by the gateway) or
 * absolute.</p>
 *
 * @param platformUrl     URL of the Linked Data Platform root as seen by browsers (for example
 *                        {@code ../platform/}: the gateway serves the platform under
 *                        {@code /platform/})
 * @param title           title shown in the user interface
 * @param apiDocsUrl      URL of the Swagger UI listing every service's API (the gateway's
 *                        {@code /swagger-ui.html})
 * @param topologyUrl     URL of the topology service ({@code /topology/} on the gateway)
 * @param transformUrl    URL of the transform service, which serves IIIF exports
 *                        ({@code /transform/} on the gateway)
 * @param communitiesPath path (below the LDP root) of the community structure
 */
@ConfigurationProperties(prefix = "articulorum.ui")
public record UiProperties(String platformUrl, String title, String apiDocsUrl, String topologyUrl,
        String transformUrl, String communitiesPath) {

    /** Platform URL used when none is configured: the gateway route, relative to {@code /ui/}. */
    public static final String DEFAULT_PLATFORM_URL = "../platform/";

    /** Title used when none is configured. */
    public static final String DEFAULT_TITLE = "Articulorum";

    /** API documentation used when none is configured: the gateway's Swagger UI. */
    public static final String DEFAULT_API_DOCS_URL = "../swagger-ui.html";

    /** Topology service used when none is configured: the gateway route. */
    public static final String DEFAULT_TOPOLOGY_URL = "../topology/";

    /** Transform service used when none is configured: the gateway route. */
    public static final String DEFAULT_TRANSFORM_URL = "../transform/";

    /** Community structure used when none is configured (the platform's default). */
    public static final String DEFAULT_COMMUNITIES_PATH = "communities";

    /**
     * Creates the settings, applying defaults to missing values.
     *
     * @param platformUrl     URL of the platform root, may be {@code null}
     * @param title           title shown in the user interface, may be {@code null}
     * @param apiDocsUrl      URL of the API documentation, may be {@code null}
     * @param topologyUrl     URL of the topology service, may be {@code null}
     * @param transformUrl    URL of the transform service, may be {@code null}
     * @param communitiesPath path of the community structure, may be {@code null}
     */
    @ConstructorBinding
    public UiProperties {
        platformUrl = valueOr(platformUrl, DEFAULT_PLATFORM_URL);
        title = valueOr(title, DEFAULT_TITLE);
        apiDocsUrl = valueOr(apiDocsUrl, DEFAULT_API_DOCS_URL);
        topologyUrl = valueOr(topologyUrl, DEFAULT_TOPOLOGY_URL);
        transformUrl = valueOr(transformUrl, DEFAULT_TRANSFORM_URL);
        communitiesPath = valueOr(communitiesPath, DEFAULT_COMMUNITIES_PATH);
    }

    /**
     * Creates the settings with only a platform URL and title; everything else takes its default.
     *
     * @param platformUrl URL of the platform root, may be {@code null}
     * @param title       title shown in the user interface, may be {@code null}
     */
    public UiProperties(final String platformUrl, final String title) {
        this(platformUrl, title, null, null, null, null);
    }

    private static String valueOr(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

}
