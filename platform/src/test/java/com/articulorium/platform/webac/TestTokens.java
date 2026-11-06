package com.articulorium.platform.webac;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signs JWT access tokens the way an OpenID Connect provider (Keycloak) does, for tests.
 */
public final class TestTokens {

    /** Issuer of the tokens. */
    public static final String ISSUER = "https://idp.example.org/realms/articulorium";

    /** Audience of the tokens. */
    public static final String AUDIENCE = "articulorium";

    private final RSAKey key;

    /** Creates a signer with a fresh RSA key. */
    public TestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The public key set, as served at the issuer's {@code jwks_uri}.
     *
     * @return the JSON of the key set
     */
    public String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /**
     * The key, for decoders that take the key directly.
     *
     * @return the RSA key (with private part)
     */
    public RSAKey key() {
        return key;
    }

    /**
     * A valid token of a user with realm roles.
     *
     * @param username the {@code preferred_username}
     * @param roles    realm roles
     * @return the compact JWS
     */
    public String token(final String username, final String... roles) {
        return sign(claims(username, roles).build());
    }

    /**
     * The claims of a valid token, for variations.
     *
     * @param username the {@code preferred_username}
     * @param roles    realm roles
     * @return the claims builder
     */
    public JWTClaimsSet.Builder claims(final String username, final String... roles) {
        final Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(UUID.randomUUID().toString())
            .audience(List.of(AUDIENCE, "account"))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("preferred_username", username)
            .claim("realm_access", Map.of("roles", List.of(roles)));
    }

    /**
     * Signs claims with this signer's key.
     *
     * @param claims the claims
     * @return the compact JWS
     */
    public String sign(final JWTClaimsSet claims) {
        return sign(claims, key);
    }

    /**
     * Signs claims with another key.
     *
     * @param claims the claims
     * @param with   the signing key
     * @return the compact JWS
     */
    public static String sign(final JWTClaimsSet claims, final RSAKey with) {
        try {
            final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(with.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(with));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

}
