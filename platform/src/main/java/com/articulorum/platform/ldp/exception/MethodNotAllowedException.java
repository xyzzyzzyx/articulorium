package com.articulorum.platform.ldp.exception;

import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * 405 Method Not Allowed: the resource does not support the request method. The response carries
 * the {@code Allow} header required by RFC 9110.
 */
public class MethodNotAllowedException extends LdpException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message
     * @param allowed the methods the resource supports
     */
    public MethodNotAllowedException(final String message, final Set<HttpMethod> allowed) {
        super(HttpStatus.METHOD_NOT_ALLOWED, message, allowHeader(allowed), null);
    }

    private static HttpHeaders allowHeader(final Set<HttpMethod> allowed) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAllow(allowed);
        return headers;
    }

}
