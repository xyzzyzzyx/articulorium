package com.articulorum.platform.webac;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of Web Access Control ({@code articulorum.webac.*}).
 *
 * <p>Access to every LDP resource is decided by the WebAC authorizations of its effective access
 * control list: its own ({@code <resource>/fcr:acl}), else the nearest ancestor's (inherited
 * through {@code acl:default}), else the {@link #getRootAcl() root ACL} configured here. Agents are
 * identified by a Consul ACL token (when {@link Consul#isEnabled() enabled}), a trusted proxy
 * header, or the servlet container's authenticated principal; everyone else is anonymous.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.webac")
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
