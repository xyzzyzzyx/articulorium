package com.articulorum.transform.spi;

/**
 * Thrown when a transformer is requested by a name that is not registered.
 */
public class UnknownTransformerException extends TransformationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param name the unknown name
     */
    public UnknownTransformerException(final String name) {
        super("Unknown transformer '" + name + "'");
    }

}
