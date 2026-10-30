package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 415 Unsupported Media Type: the request body format cannot be used for this operation. The
 * response advertises the acceptable formats in {@code Accept-Post} or {@code Accept-Patch}.
 */
public class UnsupportedMediaTypeException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message          the message
     * @param acceptHeaderName {@code Accept-Post} or {@code Accept-Patch}
     * @param acceptedTypes    the acceptable media types
     */
    public UnsupportedMediaTypeException(final String message, final String acceptHeaderName,
            final String acceptedTypes) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message, header(acceptHeaderName, acceptedTypes), null);
    }

    private static HttpHeaders header(final String name, final String value) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(name, value);
        return headers;
    }

}
