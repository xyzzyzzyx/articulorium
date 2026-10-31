package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 400 Bad Request: the request body could not be parsed.
 */
public class BadRequestException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message
     * @param cause   the parse error
     */
    public BadRequestException(final String message, final Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, new HttpHeaders(), cause);
    }

}
