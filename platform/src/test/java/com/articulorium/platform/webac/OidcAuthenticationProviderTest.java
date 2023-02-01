package com.articulorium.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.proc.JWTProcessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests {@link OidcAuthenticationProvider}.
 */
class OidcAuthenticationProviderTest {

    private final TestTokens tokens = new TestTokens();

    private WebAcProperties properties;

    private OidcAuthenticationProvider provider;

    /**
     * A processor verifying with the public key of a {@link TestTokens} and the provider's checks.
     *
     * @param tokens   the signer
     * @param settings the settings
     * @return the processor
     */
    static JWTProcessor<SecurityContext> processor(final TestTokens tokens, final WebAcProperties.Oidc settings) {
        return OidcAuthenticationProvider.processor(settings,
            new ImmutableJWKSet<>(new JWKSet(tokens.key().toPublicJWK())));
    }

    @BeforeEach
    void setUp() {
        properties = new WebAcProperties();
        properties.getOidc().setEnabled(true);
        properties.getOidc().setIssuerUri(TestTokens.ISSUER);
        properties.getOidc().setAudiences(List.of(TestTokens.AUDIENCE));
        properties.getOidc().setGroupClaims(List.of("realm_access.roles", "groups"));
        provider = new OidcAuthenticationProvider(properties, settings -> processor(tokens, settings));
    }

    private Optional<Agent> authenticate(final String token) {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return provider.authenticate(request);
    }

    @Test
    void mapsUsernameAndRolesToAgentAndGroups() {
        assertThat(authenticate(tokens.sign(tokens.claims("jane doe", "editors", "offline_access")
                .claim("groups", List.of("/curators", "/")).build())))
            .contains(new Agent("info:oidc/agent/jane%20doe", Set.of("info:oidc/group/editors",
                "info:oidc/group/offline_access", "info:oidc/group/curators"), true));
    }

    @Test
    void fallsBackToTheSubject() {
        assertThat(authenticate(tokens.sign(tokens.claims("x").claim("preferred_username", null)
                .subject("8d3f").build())))
            .map(Agent::webId).contains("info:oidc/agent/8d3f");
    }

    @Test
    void rejectsTokensNamingNoAgent() {
        properties.getOidc().setAgentClaims(List.of("email"));
        assertThatThrownBy(() -> authenticate(tokens.token("alice")))
            .isInstanceOf(AuthenticationFailedException.class).hasMessageContaining("names no agent");
    }

    @Test
    void rejectsForgedExpiredForeignAndMisdirectedTokens() {
        final String forged = TestTokens.sign(tokens.claims("alice").build(), new TestTokens().key());
        final String expired = tokens.sign(tokens.claims("alice")
            .expirationTime(Date.from(Instant.now().minusSeconds(600))).build());
        final String foreign = tokens.sign(tokens.claims("alice").issuer("https://evil.example").build());
        final String misdirected = tokens.sign(tokens.claims("alice").audience("another-service").build());

        for (final String token : List.of(forged, expired, foreign, misdirected)) {
            assertThatThrownBy(() -> authenticate(token))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("The access token is not valid");
        }
    }

    @Test
    void acceptsAnyAudienceWhenNoneIsConfigured() {
        properties.getOidc().setAudiences(List.of());
        provider = new OidcAuthenticationProvider(properties, settings -> processor(tokens, settings));
        assertThat(authenticate(tokens.sign(tokens.claims("alice").audience("another-service").build())))
            .isPresent();
    }

    @Test
    void leavesOpaqueTokensAndOtherRequestsToOtherProviders() {
        assertThat(authenticate("6f1c2a3e-1234-4cde-9abc-0123456789ab")).isEmpty();
        assertThat(provider.authenticate(new MockHttpServletRequest())).isEmpty();
        properties.getOidc().setEnabled(false);
        assertThat(authenticate(tokens.token("alice"))).isEmpty();
    }

    @Test
    void unreachableKeySetsFailAndAreRetried() {
        properties.getOidc().setJwkSetUri("http://127.0.0.1:1/certs");
        final AtomicInteger created = new AtomicInteger();
        provider = new OidcAuthenticationProvider(properties, settings -> {
            created.incrementAndGet();
            return OidcAuthenticationProvider.createProcessor(settings);
        });
        assertThatThrownBy(() -> authenticate(tokens.token("alice")))
            .isInstanceOf(AuthenticationFailedException.class).hasMessageContaining("not reachable");
        assertThatThrownBy(() -> authenticate(tokens.token("alice")))
            .isInstanceOf(AuthenticationFailedException.class);
        // the processor (and its key set cache) is kept; only the key set fetch is repeated
        assertThat(created).hasValue(1);
    }

    @Test
    void failedDiscoveryIsRetried() {
        properties.getOidc().setIssuerUri("http://127.0.0.1:1/realms/x");
        final AtomicInteger created = new AtomicInteger();
        provider = new OidcAuthenticationProvider(properties, settings -> {
            created.incrementAndGet();
            return OidcAuthenticationProvider.createProcessor(settings);
        });
        assertThatThrownBy(() -> authenticate(tokens.token("alice")))
            .isInstanceOf(AuthenticationFailedException.class).hasMessageContaining("not reachable");
        assertThatThrownBy(() -> authenticate(tokens.token("alice")))
            .isInstanceOf(AuthenticationFailedException.class);
        assertThat(created).hasValue(2);
    }

    @Test
    void discoversTheKeySetOfTheIssuer() {
        final String configuration = "{\"issuer\":\"" + TestTokens.ISSUER + "\",\"jwks_uri\":\"https://idp/certs\"}";
        final List<String> requested = new ArrayList<>();
        final ResourceRetriever retriever = url -> {
            requested.add(url.toString());
            return new Resource(configuration, "application/json");
        };
        assertThat(OidcAuthenticationProvider.discoverJwkSetUri(TestTokens.ISSUER, retriever))
            .isEqualTo("https://idp/certs");
        assertThat(requested).containsExactly(TestTokens.ISSUER + "/.well-known/openid-configuration");
        assertThatThrownBy(() -> OidcAuthenticationProvider.discoverJwkSetUri("https://other.example", retriever))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("names the issuer");
        assertThatThrownBy(() -> OidcAuthenticationProvider.discoverJwkSetUri(TestTokens.ISSUER,
                url -> new Resource("{\"issuer\":\"" + TestTokens.ISSUER + "\"}", "application/json")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no jwks_uri");
    }

    @Test
    void requiresAnIssuer() {
        properties.getOidc().setIssuerUri(" ");
        assertThatThrownBy(() -> new OidcAuthenticationProvider(properties))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("issuer-uri");
    }

    @Test
    void readsNestedClaims() {
        final Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("a")), "x.y", "flat");
        assertThat(OidcAuthenticationProvider.claim(claims, "realm_access.roles")).isEqualTo(List.of("a"));
        assertThat(OidcAuthenticationProvider.claim(claims, "x.y")).isEqualTo("flat");
        assertThat(OidcAuthenticationProvider.claim(claims, "realm_access.roles.deeper")).isNull();
        assertThat(OidcAuthenticationProvider.claim(claims, "missing")).isNull();
    }

}
