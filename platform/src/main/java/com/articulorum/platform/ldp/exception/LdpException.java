package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base class of the errors the LDP endpoints report to clients.
 *
 * <p>Each exception carries the HTTP status to answer with and any extra response headers (for
 * example {@code Allow} for 405 or {@code Accept-Patch} for 415). The message is returned as a
 * plain text body.</p>
 */
public class LdpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final transient HttpHeaders headers;

    /**
     * Creates an exception.
     *
     * @param status  the response status
     * @param message the message returned to the client
     */
    public LdpException(final HttpStatus status, final String message) {
        this(status, message, new HttpHeaders(), null);
    }

    /**
     * Creates an exception with extra headers and a cause.
     *
     * @param status  the response status
     * @param message the message returned to the client
     * @param headers extra response headers
     * @param cause   the underlying cause, may be {@code null}
     */
    public LdpException(final HttpStatus status, final String message, final HttpHeaders headers,
            final Throwable cause) {
        super(message, cause);
        this.status = status;
        this.headers = headers == null ? new HttpHeaders() : headers;
    }

    /** @return the response status */
    public HttpStatus getStatus() {
        return status;
    }

    /** @return extra response headers */
    public HttpHeaders getHeaders() {
        return headers;
    }

}
