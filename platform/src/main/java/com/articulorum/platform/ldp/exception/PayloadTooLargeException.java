package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpStatus;

/**
 * 413 Content Too Large: the request body exceeds the configured limit.
 */
public class PayloadTooLargeException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param limitBytes the configured limit in bytes
     */
    public PayloadTooLargeException(final long limitBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds the limit of " + limitBytes + " bytes");
    }

}
