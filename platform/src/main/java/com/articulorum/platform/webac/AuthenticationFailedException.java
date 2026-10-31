package com.articulorum.platform.webac;

/**
 * Thrown when a request presents credentials that cannot be validated (an unknown or revoked
 * Consul ACL token, for example). Answered with 401 Unauthorized.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why authentication failed
     */
    public AuthenticationFailedException(final String message) {
        super(message);
    }

}
