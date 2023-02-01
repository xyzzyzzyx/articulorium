package com.articulorium.platform.webac;

import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * An agent authenticated by a trusted reverse proxy ({@code articulorium.webac.trusted-agent-header}).
 *
 * <p>This is how identity providers that need a server side login flow the platform does not run
 * itself (a SAML 2.0 service provider such as mod_auth_mellon or Shibboleth, oauth2-proxy, ...)
 * plug in: the proxy authenticates the user and passes the agent's IRI in the header. Only enable
 * it when every request reaches the platform through that proxy and the proxy overwrites the
 * header; otherwise any client can claim any identity.</p>
 */
@Component
@Order(TrustedHeaderAuthenticationProvider.ORDER)
public class TrustedHeaderAuthenticationProvider implements AgentAuthenticationProvider {

    /** Position among the providers: after token based providers. */
    public static final int ORDER = 300;

    private final WebAcProperties properties;

    /**
     * Creates the provider.
     *
     * @param properties WebAC settings
     */
    public TrustedHeaderAuthenticationProvider(final WebAcProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Agent> authenticate(final HttpServletRequest request) {
        final String header = properties.getTrustedAgentHeader();
        if (!StringUtils.hasText(header)) {
            return Optional.empty();
        }
        final String agent = request.getHeader(header);
        return StringUtils.hasText(agent) ? Optional.of(Agent.of(agent.trim())) : Optional.empty();
    }

}
