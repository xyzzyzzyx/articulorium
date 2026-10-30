package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpStatus;

/**
 * 412 Precondition Failed: an {@code If-Match} or {@code If-None-Match} precondition does not hold
 * (LDP 4.2.4.5).
 */
public class PreconditionFailedException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message
     */
    public PreconditionFailedException(final String message) {
        super(HttpStatus.PRECONDITION_FAILED, message);
    }

}
