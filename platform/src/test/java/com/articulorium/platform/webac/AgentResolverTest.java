package com.articulorium.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests {@link AgentResolver} with the built-in {@link AgentAuthenticationProvider}s.
 */
class AgentResolverTest {

    private final WebAcProperties properties = new WebAcProperties();

    private final ConsulAclClient consul = mock(ConsulAclClient.class);

    private final TestTokens tokens = new TestTokens();

    private final OidcAuthenticationProvider oidc = new OidcAuthenticationProvider(properties,
        settings -> OidcAuthenticationProviderTest.processor(tokens, settings));

    // deliberately unordered: the resolver sorts by @Order
    private final AgentResolver resolver = new AgentResolver(List.of(new ContainerPrincipalAuthenticationProvider(),
        new TrustedHeaderAuthenticationProvider(properties), new ConsulTokenAuthenticationProvider(properties, consul),
        oidc));

    @Test
    void providersAreAskedInOrder() {
        assertThat(resolver.providers()).extracting(Object::getClass).containsExactly(
            OidcAuthenticationProvider.class, ConsulTokenAuthenticationProvider.class,
            TrustedHeaderAuthenticationProvider.class, ContainerPrincipalAuthenticationProvider.class);
    }

    @Test
    void anonymousWithoutCredentials() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isEqualTo(Agent.ANONYMOUS);
    }

    @Test
    void bearerTokensAreConsulTokensWhenEnabled() {
        properties.getConsul().setEnabled(true);
        final Agent agent = new Agent("info:consul/agent/x", Set.of("info:consul/role/r"), true);
        when(consul.authenticate("t0ken")).thenReturn(agent);
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t0ken");

        assertThat(resolver.resolve(request)).isEqualTo(agent);
    }

    @Test
    void consulHeaderIsAccepted() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ConsulAclClient.TOKEN_HEADER, " abc ");
        assertThat(ConsulTokenAuthenticationProvider.token(request)).isEqualTo("abc");
    }

    @Test
    void tokensAreIgnoredWhenConsulAndOidcAreDisabled() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t0ken");
        assertThat(resolver.resolve(request)).isEqualTo(Agent.ANONYMOUS);

        final MockHttpServletRequest jwt = new MockHttpServletRequest();
        jwt.addHeader("Authorization", "Bearer " + tokens.token("alice"));
        assertThat(resolver.resolve(jwt)).isEqualTo(Agent.ANONYMOUS);
        verifyNoInteractions(consul);
    }

    @Test
    void jwtsGoToOpenIdConnectAndOpaqueTokensToConsul() {
        properties.getOidc().setEnabled(true);
        properties.getOidc().setIssuerUri(TestTokens.ISSUER);
        properties.getConsul().setEnabled(true);
        when(consul.authenticate("6f1c2a3e-1234")).thenReturn(Agent.of("info:consul/agent/bob"));

        final MockHttpServletRequest jwt = new MockHttpServletRequest();
        jwt.addHeader("Authorization", "Bearer " + tokens.token("alice", "editors"));
        assertThat(resolver.resolve(jwt)).isEqualTo(
            new Agent("info:oidc/agent/alice", Set.of(), true));

        final MockHttpServletRequest opaque = new MockHttpServletRequest();
        opaque.addHeader("Authorization", "Bearer 6f1c2a3e-1234");
        assertThat(resolver.resolve(opaque).webId()).isEqualTo("info:consul/agent/bob");
    }

    @Test
    void invalidCredentialsStopTheChain() {
        properties.getOidc().setEnabled(true);
        properties.getOidc().setIssuerUri(TestTokens.ISSUER);
        properties.setTrustedAgentHeader("X-Forwarded-User");
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TestTokens.sign(tokens.claims("mallory").build(),
            new TestTokens().key()));
        request.addHeader("X-Forwarded-User", "https://example.org/mallory#me");

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void trustedHeaderAndPrincipal() {
        properties.setTrustedAgentHeader("X-Forwarded-User");
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-User", "https://example.org/a#me");
        assertThat(resolver.resolve(request).webId()).isEqualTo("https://example.org/a#me");

        final MockHttpServletRequest withPrincipal = new MockHttpServletRequest();
        withPrincipal.setUserPrincipal(() -> "container-user");
        assertThat(resolver.resolve(withPrincipal)).isEqualTo(Agent.of("container-user"));
    }

    @Test
    void additionalProvidersPlugIn() {
        final AgentAuthenticationProvider saml = request -> Optional.ofNullable(request.getHeader("X-Saml-NameID"))
            .map(name -> Agent.of("info:saml/agent/" + name));
        final AgentResolver withSaml = new AgentResolver(List.of(saml, new ContainerPrincipalAuthenticationProvider()));
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Saml-NameID", "jdoe");

        assertThat(withSaml.resolve(request).webId()).isEqualTo("info:saml/agent/jdoe");
    }

}
