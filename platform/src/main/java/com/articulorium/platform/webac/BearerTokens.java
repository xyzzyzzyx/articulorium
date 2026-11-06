package com.articulorium.platform.webac;

import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads bearer tokens ({@code Authorization: Bearer <token>}, RFC 6750) from requests.
 */
public final class BearerTokens {

    private static final String BEARER = "Bearer ";

    /** Header, payload and signature of a JWS in compact serialization (RFC 7515). */
    private static final Pattern JWS = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private BearerTokens() {
    }

    /**
     * Extracts the bearer token of a request.
     *
     * @param request the request
     * @return the token, or {@code null} when the request has none
     */
    public static String bearer(final HttpServletRequest request) {
        final String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            final String token = authorization.substring(BEARER.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /**
     * Whether a token has the shape of a signed JWT. Opaque tokens (Consul ACL secret IDs, for
     * example) do not.
     *
     * @param token the token
     * @return {@code true} for three dot separated base64url segments
     */
    public static boolean isJwt(final String token) {
        return token != null && JWS.matcher(token).matches();
    }

}
