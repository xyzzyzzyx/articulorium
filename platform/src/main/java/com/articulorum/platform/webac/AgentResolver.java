package com.articulorum.platform.webac;

import java.security.Principal;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Determines the {@link Agent} making a request.
 *
 * <ol>
 *   <li>With Consul ACLs enabled, a token in {@code Authorization: Bearer} or
 *       {@code X-Consul-Token} is validated with Consul; an invalid token fails the request.</li>
 *   <li>With a trusted agent header configured, its value is the agent.</li>
 *   <li>A principal authenticated by the servlet container is the agent.</li>
 *   <li>Otherwise the request is anonymous.</li>
 * </ol>
 */
@Component
public class AgentResolver {

    private static final String BEARER = "Bearer ";

    private final WebAcProperties properties;

    private final ConsulAclClient consul;

    /**
     * Creates the resolver.
     *
     * @param properties WebAC settings
     * @param consul     Consul ACL token validation
     */
    public AgentResolver(final WebAcProperties properties, final ConsulAclClient consul) {
        this.properties = properties;
        this.consul = consul;
    }

    /**
     * Resolves the agent of a request.
     *
     * @param request the request
     * @return the agent, {@link Agent#ANONYMOUS} without credentials
     * @throws AuthenticationFailedException when presented credentials are invalid
     */
    public Agent resolve(final HttpServletRequest request) {
        if (properties.getConsul().isEnabled()) {
            final String token = token(request);
            if (token != null) {
                return consul.authenticate(token);
            }
        }
        if (StringUtils.hasText(properties.getTrustedAgentHeader())) {
            final String agent = request.getHeader(properties.getTrustedAgentHeader());
            if (StringUtils.hasText(agent)) {
                return Agent.of(agent.trim());
            }
        }
        final Principal principal = request.getUserPrincipal();
        if (principal != null && StringUtils.hasText(principal.getName())) {
            return Agent.of(principal.getName());
        }
        return Agent.ANONYMOUS;
    }

    /**
     * Extracts a Consul token from a request.
     *
     * @param request the request
     * @return the token, or {@code null}
     */
    static String token(final HttpServletRequest request) {
        final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            final String token = authorization.substring(BEARER.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        final String header = request.getHeader(ConsulAclClient.TOKEN_HEADER);
        return StringUtils.hasText(header) ? header.trim() : null;
    }

}
