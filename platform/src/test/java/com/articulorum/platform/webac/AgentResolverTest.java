package com.articulorum.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests {@link AgentResolver}.
 */
class AgentResolverTest {

    private final WebAcProperties properties = new WebAcProperties();

    private final ConsulAclClient consul = mock(ConsulAclClient.class);

    private final AgentResolver resolver = new AgentResolver(properties, consul);

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
        assertThat(AgentResolver.token(request)).isEqualTo("abc");
    }

    @Test
    void tokensAreIgnoredWhenConsulIsDisabled() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t0ken");
        assertThat(resolver.resolve(request)).isEqualTo(Agent.ANONYMOUS);
        verifyNoInteractions(consul);
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

}
