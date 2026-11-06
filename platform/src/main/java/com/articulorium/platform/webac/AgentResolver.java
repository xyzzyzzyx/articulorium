package com.articulorium.platform.webac;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Determines the {@link Agent} making a request by asking every
 * {@link AgentAuthenticationProvider} in order; the first provider that recognises the request's
 * credentials decides. With the built-in providers:
 *
 * <ol>
 *   <li>OpenID Connect: a JWT bearer token validated against the configured issuer (Keycloak by
 *       default); an invalid token fails the request.</li>
 *   <li>Consul ACLs (when enabled): any other token in {@code Authorization: Bearer} or
 *       {@code X-Consul-Token}, validated with Consul; an invalid token fails the request.</li>
 *   <li>A trusted agent header, when configured.</li>
 *   <li>A principal authenticated by the servlet container.</li>
 * </ol>
 *
 * <p>Without recognised credentials the request is anonymous.</p>
 */
@Component
public class AgentResolver {

    private final List<AgentAuthenticationProvider> providers;

    /**
     * Creates the resolver.
     *
     * @param providers the identity providers, in any order (they are sorted by their
     *                  {@link org.springframework.core.annotation.Order})
     */
    public AgentResolver(final List<AgentAuthenticationProvider> providers) {
        final List<AgentAuthenticationProvider> sorted = new ArrayList<>(providers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.providers = List.copyOf(sorted);
    }

    /**
     * Resolves the agent of a request.
     *
     * @param request the request
     * @return the agent, {@link Agent#ANONYMOUS} without credentials
     * @throws AuthenticationFailedException when presented credentials are invalid
     */
    public Agent resolve(final HttpServletRequest request) {
        for (final AgentAuthenticationProvider provider : providers) {
            final Optional<Agent> agent = provider.authenticate(request);
            if (agent.isPresent()) {
                return agent.get();
            }
        }
        return Agent.ANONYMOUS;
    }

    /**
     * The providers in the order they are asked.
     *
     * @return the providers
     */
    public List<AgentAuthenticationProvider> providers() {
        return providers;
    }

}
