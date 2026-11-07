package com.articulorium.service.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Settings handed to the user interface ({@code articulorium.ui.*}).
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
 * @param profilesPath    path (below the LDP root) of the academic profiles (VIVO)
 * @param auth            how users sign in
 * @param harvestUrl      URL of the harvest service's API ({@code /harvest/} on the gateway)
 */
@ConfigurationProperties(prefix = "articulorium.ui")
public record UiProperties(String platformUrl, String title, String apiDocsUrl, String topologyUrl,
        String transformUrl, String communitiesPath, String profilesPath, Auth auth,
        String harvestUrl) {

    /** Platform URL used when none is configured: the gateway route, relative to {@code /ui/}. */
    public static final String DEFAULT_PLATFORM_URL = "../platform/";

    /** Title used when none is configured. */
    public static final String DEFAULT_TITLE = "Articulorium";

    /** API documentation used when none is configured: the gateway's Swagger UI. */
    public static final String DEFAULT_API_DOCS_URL = "../swagger-ui.html";

    /** Topology service used when none is configured: the gateway route. */
    public static final String DEFAULT_TOPOLOGY_URL = "../topology/";

    /** Transform service used when none is configured: the gateway route. */
    public static final String DEFAULT_TRANSFORM_URL = "../transform/";

    /** Harvest service used when none is configured: the gateway route. */
    public static final String DEFAULT_HARVEST_URL = "../harvest/";

    /** Community structure used when none is configured (the platform's default). */
    public static final String DEFAULT_COMMUNITIES_PATH = "communities";

    /** Container of the academic profiles used when none is configured (the platform's default). */
    public static final String DEFAULT_PROFILES_PATH = "profiles";

    /**
     * Creates the settings, applying defaults to missing values.
     *
     * @param platformUrl     URL of the platform root, may be {@code null}
     * @param title           title shown in the user interface, may be {@code null}
     * @param apiDocsUrl      URL of the API documentation, may be {@code null}
     * @param topologyUrl     URL of the topology service, may be {@code null}
     * @param transformUrl    URL of the transform service, may be {@code null}
     * @param communitiesPath path of the community structure, may be {@code null}
     * @param profilesPath    path of the academic profiles, may be {@code null}
     * @param auth            sign-in settings, may be {@code null}
     * @param harvestUrl      URL of the harvest service, may be {@code null}
     */
    @ConstructorBinding
    public UiProperties {
        platformUrl = valueOr(platformUrl, DEFAULT_PLATFORM_URL);
        title = valueOr(title, DEFAULT_TITLE);
        apiDocsUrl = valueOr(apiDocsUrl, DEFAULT_API_DOCS_URL);
        topologyUrl = valueOr(topologyUrl, DEFAULT_TOPOLOGY_URL);
        transformUrl = valueOr(transformUrl, DEFAULT_TRANSFORM_URL);
        communitiesPath = valueOr(communitiesPath, DEFAULT_COMMUNITIES_PATH);
        profilesPath = valueOr(profilesPath, DEFAULT_PROFILES_PATH);
        auth = auth == null ? new Auth(null, null) : auth;
        harvestUrl = valueOr(harvestUrl, DEFAULT_HARVEST_URL);
    }

    /**
     * Creates the settings with the default sign-in and profile container.
     *
     * @param platformUrl     URL of the platform root, may be {@code null}
     * @param title           title shown in the user interface, may be {@code null}
     * @param apiDocsUrl      URL of the API documentation, may be {@code null}
     * @param topologyUrl     URL of the topology service, may be {@code null}
     * @param transformUrl    URL of the transform service, may be {@code null}
     * @param communitiesPath path of the community structure, may be {@code null}
     */
    public UiProperties(final String platformUrl, final String title, final String apiDocsUrl,
            final String topologyUrl, final String transformUrl, final String communitiesPath) {
        this(platformUrl, title, apiDocsUrl, topologyUrl, transformUrl, communitiesPath, null,
            null, null);
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

    /**
     * How users sign in ({@code articulorium.ui.auth.*}). The user interface only knows the kind
     * of identity provider, never a product: any OpenID Connect provider works with {@code oidc}.
     *
     * @param provider {@code oidc} (redirect to an OpenID Connect provider, Keycloak by default),
     *                 {@code token} (paste a token, such as a Consul ACL token) or {@code none}
     *                 (no sign-in)
     * @param oidc     the OpenID Connect client settings, used with {@code oidc}
     */
    public record Auth(String provider, Oidc oidc) {

        /** Signs in with an OpenID Connect provider. */
        public static final String OIDC = "oidc";

        /** Signs in with a pasted bearer token. */
        public static final String TOKEN = "token";

        /** Offers no sign-in. */
        public static final String NONE = "none";

        /**
         * Creates the settings, applying defaults to missing values.
         *
         * @param provider the kind of provider, may be {@code null}
         * @param oidc     the OpenID Connect settings, may be {@code null}
         * @throws IllegalArgumentException for an unknown provider
         */
        public Auth {
            provider = valueOr(provider, OIDC).toLowerCase(Locale.ROOT);
            if (!OIDC.equals(provider) && !TOKEN.equals(provider) && !NONE.equals(provider)) {
                throw new IllegalArgumentException(
                    "articulorium.ui.auth.provider must be oidc, token or none, not " + provider);
            }
            oidc = oidc == null ? new Oidc(null, null, null) : oidc;
        }

    }

    /**
     * The user interface as an OpenID Connect client: a public client using the authorization code
     * flow with PKCE.
     *
     * @param authority the issuer, relative to the user interface or absolute; its
     *                  {@code /.well-known/openid-configuration} must be reachable by browsers
     * @param clientId  the client registered with the provider
     * @param scope     requested scopes
     */
    public record Oidc(String authority, String clientId, String scope) {

        /** Issuer used when none is configured: the docker compose Keycloak realm. */
        public static final String DEFAULT_AUTHORITY = "../auth/realms/articulorium";

        /** Client used when none is configured. */
        public static final String DEFAULT_CLIENT_ID = "articulorium-ui";

        /** Scopes used when none are configured. */
        public static final String DEFAULT_SCOPE = "openid profile";

        /**
         * Creates the settings, applying defaults to missing values.
         *
         * @param authority the issuer, may be {@code null}
         * @param clientId  the client, may be {@code null}
         * @param scope     the scopes, may be {@code null}
         */
        public Oidc {
            authority = valueOr(authority, DEFAULT_AUTHORITY);
            clientId = valueOr(clientId, DEFAULT_CLIENT_ID);
            scope = valueOr(scope, DEFAULT_SCOPE);
        }

    }

    private static String valueOr(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

}
