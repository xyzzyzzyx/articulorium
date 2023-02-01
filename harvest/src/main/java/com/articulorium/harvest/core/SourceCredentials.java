package com.articulorium.harvest.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * How to authenticate to a source. Never printed: {@link #toString()} hides the secrets.
 *
 * @param username the user name (HTTP Basic, VIVO account e-mail), may be {@code null}
 * @param password the password, may be {@code null}
 * @param token    a bearer token, may be {@code null}
 */
public record SourceCredentials(String username, String password, String token) {

    /** No credentials. */
    public static final SourceCredentials NONE = new SourceCredentials(null, null, null);

    /**
     * Tells whether there is a user name.
     *
     * @return {@code true} when a user name is set
     */
    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    /**
     * Tells whether there is a token.
     *
     * @return {@code true} when a token is set
     */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /**
     * Returns the {@code Authorization} header value for these credentials: the bearer token, else
     * HTTP Basic with the user name and password.
     *
     * @return the header value, or {@code null} without credentials
     */
    public String authorization() {
        if (hasToken()) {
            return "Bearer " + token.trim();
        }
        if (hasUsername()) {
            final String pair = username + ":" + (password == null ? "" : password);
            return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    @Override
    public String toString() {
        return "SourceCredentials[username=" + (hasUsername() ? username : "") + ", password="
            + (password == null ? "" : "***") + ", token=" + (hasToken() ? "***" : "") + "]";
    }

}
