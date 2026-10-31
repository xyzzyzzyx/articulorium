package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 Conflict: the request violates one of the server's published constraints (for example it
 * tries to change server-managed triples or the interaction model). The response always carries
 * the {@code Link: rel="http://www.w3.org/ns/ldp#constrainedBy"} header (LDP 4.2.1.6).
 */
public class ConstraintViolationException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was violated
     */
    public ConstraintViolationException(final String message) {
        super(HttpStatus.CONFLICT, message);
    }

}
