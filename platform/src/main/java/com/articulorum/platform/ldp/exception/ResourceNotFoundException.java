package com.articulorum.platform.ldp.exception;

import org.springframework.http.HttpStatus;

/**
 * 404 Not Found: no resource exists at the requested path.
 */
public class ResourceNotFoundException extends LdpException {

    private static final long serialVersionUID = 1L;

    /** Message template; the argument is the resource path. */
    public static final String TEMPLATE = "Resource %s was not found";

    /**
     * Creates the exception.
     *
     * @param path the missing path
     */
    public ResourceNotFoundException(final String path) {
        super(HttpStatus.NOT_FOUND, String.format(TEMPLATE, "/" + path));
    }

}
