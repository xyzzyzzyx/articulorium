package com.articulorium.platform.webac;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A source of agent identities: turns the credentials of a request into a WebAC {@link Agent}.
 *
 * <p>This is the extension point between Web Access Control and identity providers. The platform
 * ships providers for OpenID Connect / OAuth 2.0 access tokens (JWTs from any compliant issuer,
 * Keycloak by default: {@link OidcAuthenticationProvider}), Consul ACL tokens
 * ({@link ConsulTokenAuthenticationProvider}), a header set by a trusted authenticating proxy (for
 * example a SAML service provider: {@link TrustedHeaderAuthenticationProvider}) and the servlet
 * container's principal ({@link ContainerPrincipalAuthenticationProvider}). Another provider is a
 * Spring bean implementing this interface; {@link AgentResolver} asks the providers in their
 * {@link org.springframework.core.annotation.Order order} and uses the first answer.</p>
 *
 * <p>Implementations must not let provider specific types escape: WebAC only sees agents and
 * groups as IRIs.</p>
 */
public interface AgentAuthenticationProvider {

    /**
     * Authenticates a request.
     *
     * @param request the request
     * @return the agent, or empty when the request carries no credentials this provider handles
     *         (the next provider is asked)
     * @throws AuthenticationFailedException when the request carries credentials this provider
     *         handles but they are invalid, expired or cannot be checked; the request is answered
     *         with 401 and no other provider is asked
     */
    Optional<Agent> authenticate(HttpServletRequest request);

}
