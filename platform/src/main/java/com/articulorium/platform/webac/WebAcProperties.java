package com.articulorium.platform.webac;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of Web Access Control ({@code articulorium.webac.*}).
 *
 * <p>Access to every LDP resource is decided by the WebAC authorizations of its effective access
 * control list: its own ({@code <resource>/fcr:acl}), else the nearest ancestor's (inherited
 * through {@code acl:default}), else the {@link #getRootAcl() root ACL} configured here. Agents are
 * identified by an OpenID Connect access token (when {@link Oidc#isEnabled() enabled}), a Consul
 * ACL token (when {@link Consul#isEnabled() enabled}), a trusted proxy header, or the servlet
 * container's authenticated principal (see {@link AgentAuthenticationProvider}); everyone else is
 * anonymous.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.webac")
public class WebAcProperties {

    /** Whether access control is enforced. When disabled every request is allowed. */
    private boolean enabled = true;

    /**
     * Turtle document with the authorizations that apply when no resource on the path has an ACL;
     * {@code <>} denotes the root container. The default grants everyone full access, which keeps a
     * fresh repository usable; restrict it for production.
     */
    private Resource rootAcl = new ClassPathResource("webac/root-acl.ttl");

    /**
     * Name of a request header carrying the agent (WebID) authenticated by a trusted reverse proxy,
     * for example {@code X-Forwarded-User}. Leave empty unless every request passes that proxy.
     */
    private String trustedAgentHeader;

    /** Consul ACL integration. */
    private Consul consul = new Consul();

    /** OpenID Connect / OAuth 2.0 access tokens (Keycloak by default). */
    private Oidc oidc = new Oidc();

    /**
     * OpenID Connect / OAuth 2.0 JWT access tokens as agent identities.
     *
     * <p>A request presenting a JWT ({@code Authorization: Bearer <jwt>}) is authenticated by
     * verifying the token's signature with the issuer's JSON Web Key Set and checking its issuer,
     * validity period and (when {@link #getAudiences() configured}) audience. The agent is
     * {@link #getAgentPrefix()} followed by the first of {@link #getAgentClaims()} the token has;
     * string values of {@link #getGroupClaims()} become agent groups ({@link #getGroupPrefix()}
     * followed by the value), usable with {@code acl:agentGroup}. Nothing here is specific to one
     * identity provider: Keycloak, Entra ID, Auth0, Okta, Dex, ... differ only in these values.</p>
     */
    @Getter
    @Setter
    public static class Oidc {

        /** Whether JWT access tokens are accepted. */
        private boolean enabled;

        /**
         * The issuer identifier ({@code iss}) tokens must carry, for example
         * {@code https://idp.example.org/realms/articulorium}. Required when enabled.
         */
        private String issuerUri;

        /**
         * Where the issuer's JSON Web Key Set is fetched. When empty it is discovered from the
         * issuer's {@code /.well-known/openid-configuration}. Set it when the platform reaches the
         * identity provider at another address than the public issuer identifier (inside Docker
         * Compose, for example).
         */
        private String jwkSetUri;

        /** Accepted audiences ({@code aud}); a token must name at least one. Empty accepts any. */
        private List<String> audiences = new ArrayList<>();

        /** Accepted JWS signature algorithms. */
        private List<String> jwsAlgorithms = new ArrayList<>(List.of("RS256"));

        /** Claims naming the agent, in order of preference. */
        private List<String> agentClaims = new ArrayList<>(List.of("preferred_username", "sub"));

        /**
         * Claims listing the agent's groups or roles; a dot separates the names of nested claims
         * ({@code realm_access.roles} for Keycloak realm roles).
         */
        private List<String> groupClaims = new ArrayList<>(List.of("groups"));

        /** Prefix of agent IRIs minted from the agent claim. */
        private String agentPrefix = "info:oidc/agent/";

        /** Prefix of group IRIs minted from group claim values. */
        private String groupPrefix = "info:oidc/group/";

        /** Tolerated difference between the identity provider's clock and this server's. */
        private Duration clockSkew = Duration.ofSeconds(60);

    }

    /**
     * Consul ACL tokens as agent identities.
     *
     * <p>A request presenting a token ({@code Authorization: Bearer <token>} or
     * {@code X-Consul-Token}) is authenticated by asking Consul for the token's details
     * ({@code GET /v1/acl/token/self}). The agent is {@link #getAgentPrefix()} followed by the
     * token's description (or its accessor ID when the description is empty); the token's roles and
     * policies become agent groups ({@link #getRolePrefix()}, {@link #getPolicyPrefix()} followed by
     * their names), usable with {@code acl:agentGroup}.</p>
     */
    @Getter
    @Setter
    public static class Consul {

        /** Whether Consul ACL tokens are accepted. */
        private boolean enabled;

        /** Base URL of the Consul HTTP API. */
        private String url = "http://consul:8500";

        /** How long a validated token is remembered. */
        private Duration cacheTtl = Duration.ofSeconds(60);

        /** Prefix of agent IRIs minted from token descriptions or accessor IDs. */
        private String agentPrefix = "info:consul/agent/";

        /** Prefix of group IRIs minted from Consul role names. */
        private String rolePrefix = "info:consul/role/";

        /** Prefix of group IRIs minted from Consul policy names. */
        private String policyPrefix = "info:consul/policy/";

    }

}
