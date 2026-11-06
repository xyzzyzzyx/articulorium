package com.articulorium.platform.webac;

import java.security.Principal;
import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A principal authenticated by the servlet container (or a servlet filter running before Web
 * Access Control) is the agent.
 */
@Component
@Order(ContainerPrincipalAuthenticationProvider.ORDER)
public class ContainerPrincipalAuthenticationProvider implements AgentAuthenticationProvider {

    /** Position among the providers: last. */
    public static final int ORDER = 400;

    @Override
    public Optional<Agent> authenticate(final HttpServletRequest request) {
        final Principal principal = request.getUserPrincipal();
        return principal != null && StringUtils.hasText(principal.getName())
            ? Optional.of(Agent.of(principal.getName()))
            : Optional.empty();
    }

}
