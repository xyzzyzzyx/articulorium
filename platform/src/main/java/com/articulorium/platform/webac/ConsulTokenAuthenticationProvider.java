package com.articulorium.platform.webac;

import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Consul ACL tokens as agent identities ({@code articulorium.webac.consul.enabled}).
 *
 * <p>Handles {@code X-Consul-Token} and any bearer token that an earlier provider (OpenID Connect
 * for JWTs) did not claim; validation and the mapping to agents and groups are done by
 * {@link ConsulAclClient}.</p>
 */
@Component
@Order(ConsulTokenAuthenticationProvider.ORDER)
public class ConsulTokenAuthenticationProvider implements AgentAuthenticationProvider {

    /** Position among the providers: after OpenID Connect. */
    public static final int ORDER = 200;

    private final WebAcProperties properties;

    private final ConsulAclClient consul;

    /**
     * Creates the provider.
     *
     * @param properties WebAC settings
     * @param consul     Consul ACL token validation
     */
    public ConsulTokenAuthenticationProvider(final WebAcProperties properties, final ConsulAclClient consul) {
        this.properties = properties;
        this.consul = consul;
    }

    @Override
    public Optional<Agent> authenticate(final HttpServletRequest request) {
        if (!properties.getConsul().isEnabled()) {
            return Optional.empty();
        }
        final String token = token(request);
        return token == null ? Optional.empty() : Optional.of(consul.authenticate(token));
    }

    /**
     * Extracts a Consul token from a request: the bearer token, else {@code X-Consul-Token}.
     *
     * @param request the request
     * @return the token, or {@code null}
     */
    static String token(final HttpServletRequest request) {
        final String bearer = BearerTokens.bearer(request);
        if (bearer != null) {
            return bearer;
        }
        final String header = request.getHeader(ConsulAclClient.TOKEN_HEADER);
        return StringUtils.hasText(header) ? header.trim() : null;
    }

}
