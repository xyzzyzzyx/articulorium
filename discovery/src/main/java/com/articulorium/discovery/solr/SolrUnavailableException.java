package com.articulorium.discovery.solr;

/**
 * The search index could not answer. The message is safe to show: it never carries Solr's own
 * diagnostics.
 */
public class SolrUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed, safe to show to callers
     */
    public SolrUnavailableException(final String message) {
        super(message);
    }

}
