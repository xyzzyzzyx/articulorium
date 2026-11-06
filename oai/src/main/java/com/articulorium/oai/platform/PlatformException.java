package com.articulorium.oai.platform;

import lombok.Getter;

/**
 * The platform could not answer a request of this service (unreachable, failed, or refused it).
 */
@Getter
public class PlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The platform's HTTP status, or 502 when it gave none. */
    private final int status;

    /**
     * Creates the exception.
     *
     * @param status  the platform's status, or 502
     * @param message what failed
     * @param cause   the cause, may be {@code null}
     */
    public PlatformException(final int status, final String message, final Throwable cause) {
        super(message, cause);
        this.status = status;
    }

}
