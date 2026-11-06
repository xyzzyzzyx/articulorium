package com.articulorium.platform.webac;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenID Connect / OAuth 2.0 JWT access tokens as agent identities
 * ({@code articulorium.webac.oidc.*}).
 *
 * <p>Works with any issuer that signs access tokens as JWTs and publishes a JSON Web Key Set:
 * Keycloak (the default of the Docker Compose stack), Entra ID, Auth0, Okta, Dex, ... The token's
 * signature, issuer, validity period and audience are checked; the agent and its groups come from
 * configurable claims ({@link WebAcProperties.Oidc}). Only bearer tokens shaped like a JWT are
 * handled, so opaque tokens (Consul ACL tokens) reach the next provider.</p>
 *
 * <p>The token processor, and with it the issuer's discovery document, is created on the first
 * token; the platform therefore starts without the identity provider, and a failed discovery is
 * retried with the next token. The key set is cached and refreshed when a token names an unknown
 * key (key rotation).</p>
 */
@Slf4j
@Component
@Order(OidcAuthenticationProvider.ORDER)
public class OidcAuthenticationProvider implements AgentAuthenticationProvider {

    /** Position among the providers: first. */
    public static final int ORDER = 100;

    /** Timeout of key set and discovery requests, in milliseconds. */
    private static final int HTTP_TIMEOUT_MILLIS = 5000;

    /** Largest key set or discovery document accepted, in bytes. */
    private static final int HTTP_SIZE_LIMIT = 512 * 1024;

    private final WebAcProperties properties;

    private final Function<WebAcProperties.Oidc, JWTProcessor<SecurityContext>> processors;

    private volatile JWTProcessor<SecurityContext> processor;

    /**
     * Creates the provider.
     *
     * @param properties WebAC settings
     */
    @Autowired
    public OidcAuthenticationProvider(final WebAcProperties properties) {
        this(properties, OidcAuthenticationProvider::createProcessor);
    }

    /**
     * Creates the provider with a token processor factory (for tests).
     *
     * @param properties WebAC settings
     * @param processors creates the token processor from the settings
     */
    OidcAuthenticationProvider(final WebAcProperties properties,
            final Function<WebAcProperties.Oidc, JWTProcessor<SecurityContext>> processors) {
        this.properties = properties;
        this.processors = processors;
        final WebAcProperties.Oidc oidc = properties.getOidc();
        if (oidc.isEnabled() && !StringUtils.hasText(oidc.getIssuerUri())) {
            throw new IllegalStateException("articulorium.webac.oidc.issuer-uri is required when OpenID Connect is enabled");
        }
    }

    @Override
    public Optional<Agent> authenticate(final HttpServletRequest request) {
        final WebAcProperties.Oidc oidc = properties.getOidc();
        if (!oidc.isEnabled()) {
            return Optional.empty();
        }
        final String token = BearerTokens.bearer(request);
        if (!BearerTokens.isJwt(token)) {
            return Optional.empty();
        }
        final JWTClaimsSet claims;
        try {
            claims = processor(oidc).process(token, null);
        } catch (KeySourceException | IllegalStateException e) {
            log.warn("Unable to validate an access token with {}: {}", oidc.getIssuerUri(), e.getMessage());
            throw new AuthenticationFailedException("The identity provider is not reachable to validate the token");
        } catch (ParseException | BadJOSEException | JOSEException e) {
            // malformed, wrong signature, expired, other issuer or audience: say no more than that
            log.debug("Rejected an access token: {}", e.getMessage());
            throw new AuthenticationFailedException("The access token is not valid");
        }
        return Optional.of(toAgent(claims.getClaims(), oidc));
    }

    private JWTProcessor<SecurityContext> processor(final WebAcProperties.Oidc oidc) {
        JWTProcessor<SecurityContext> current = processor;
        if (current == null) {
            synchronized (this) {
                current = processor;
                if (current == null) {
                    current = processors.apply(oidc);
                    processor = current;
                }
            }
        }
        return current;
    }

    /**
     * Creates a processor verifying signatures with the configured (or discovered) key set and
     * validating issuer, validity period and audience.
     *
     * @param oidc the settings
     * @return the processor
     * @throws IllegalStateException when the key set location cannot be discovered
     */
    static JWTProcessor<SecurityContext> createProcessor(final WebAcProperties.Oidc oidc) {
        final ResourceRetriever retriever =
            new DefaultResourceRetriever(HTTP_TIMEOUT_MILLIS, HTTP_TIMEOUT_MILLIS, HTTP_SIZE_LIMIT);
        final String jwkSetUri = StringUtils.hasText(oidc.getJwkSetUri()) ? oidc.getJwkSetUri()
            : discoverJwkSetUri(oidc.getIssuerUri(), retriever);
        final JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.create(URI.create(jwkSetUri).toURL(), retriever).build();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid JWK set URI " + jwkSetUri, e);
        }
        return processor(oidc, keys);
    }

    /**
     * Creates a processor verifying signatures with keys of a key source.
     *
     * @param oidc the settings
     * @param keys the issuer's keys
     * @return the processor
     */
    static JWTProcessor<SecurityContext> processor(final WebAcProperties.Oidc oidc,
            final JWKSource<SecurityContext> keys) {
        final Set<JWSAlgorithm> algorithms = oidc.getJwsAlgorithms().stream()
            .map(JWSAlgorithm::parse).collect(Collectors.toUnmodifiableSet());
        final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));
        final DefaultJWTClaimsVerifier<SecurityContext> verifier = new DefaultJWTClaimsVerifier<>(
            oidc.getAudiences().isEmpty() ? null : Set.copyOf(oidc.getAudiences()),
            new JWTClaimsSet.Builder().issuer(oidc.getIssuerUri()).build(),
            Set.of("iss", "exp"),
            null);
        verifier.setMaxClockSkew((int) oidc.getClockSkew().toSeconds());
        processor.setJWTClaimsSetVerifier(verifier);
        return processor;
    }

    /**
     * Reads the key set location from the issuer's OpenID Provider Configuration.
     *
     * @param issuer    the issuer identifier
     * @param retriever fetches the document
     * @return the {@code jwks_uri}
     * @throws IllegalStateException when the document cannot be read or belongs to another issuer
     */
    static String discoverJwkSetUri(final String issuer, final ResourceRetriever retriever) {
        final String location = (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
            + "/.well-known/openid-configuration";
        try {
            final URL url = URI.create(location).toURL();
            final Map<String, Object> configuration = JSONObjectUtils.parse(retriever.retrieveResource(url).getContent());
            if (!issuer.equals(configuration.get("issuer"))) {
                throw new IllegalStateException("The OpenID Provider Configuration at " + location
                    + " names the issuer " + configuration.get("issuer"));
            }
            if (!(configuration.get("jwks_uri") instanceof String jwksUri) || jwksUri.isBlank()) {
                throw new IllegalStateException("The OpenID Provider Configuration at " + location + " has no jwks_uri");
            }
            return jwksUri;
        } catch (IOException | ParseException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to read " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Maps validated claims to an agent.
     *
     * @param claims   the token's claims
     * @param settings naming settings
     * @return the agent
     * @throws AuthenticationFailedException when no agent claim is present
     */
    static Agent toAgent(final Map<String, Object> claims, final WebAcProperties.Oidc settings) {
        String name = null;
        for (final String claim : settings.getAgentClaims()) {
            if (claim(claims, claim) instanceof String text && !text.isBlank()) {
                name = text.trim();
                break;
            }
        }
        if (name == null) {
            throw new AuthenticationFailedException("The access token names no agent");
        }
        final Set<String> groups = new LinkedHashSet<>();
        for (final String claim : settings.getGroupClaims()) {
            final Object value = claim(claims, claim);
            final Collection<?> values = value instanceof Collection<?> collection ? collection
                : value == null ? List.of() : List.of(value);
            for (final Object group : values) {
                if (group instanceof String text) {
                    // Keycloak's group membership mapper writes full paths such as "/editors"
                    final String trimmed = text.trim().replaceFirst("^/+", "");
                    if (!trimmed.isEmpty()) {
                        groups.add(settings.getGroupPrefix() + encode(trimmed));
                    }
                }
            }
        }
        return new Agent(settings.getAgentPrefix() + encode(name), groups, true);
    }

    /**
     * Reads a claim, following dots into nested objects.
     *
     * @param claims the claims
     * @param path   for example {@code realm_access.roles}
     * @return the value, or {@code null}
     */
    static Object claim(final Map<String, Object> claims, final String path) {
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object current = claims;
        for (final String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
