package com.articulorium.oai.protocol;

import lombok.Getter;

/**
 * An OAI-PMH error condition: the request is answered with an {@code <error>} element.
 */
@Getter
public class OaiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The error condition. */
    private final OaiError error;

    /**
     * Creates the exception.
     *
     * @param error   the error condition
     * @param message the human readable description written in the error element
     */
    public OaiException(final OaiError error, final String message) {
        super(message);
        this.error = error;
    }

}
