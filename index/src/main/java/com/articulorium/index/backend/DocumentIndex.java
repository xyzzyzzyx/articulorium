package com.articulorium.index.backend;

import java.util.List;
import java.util.Map;

/**
 * The search index the service keeps in sync with the platform: Solr by default, or Elasticsearch
 * ({@code articulorium.index.backend}).
 *
 * <p>Documents are the flat field/value maps the transform service produces; their field names are
 * the same in every backend (see {@code README.md}, "Search index"). Implementations talk to their
 * backend over HTTP and report failures as
 * {@link org.springframework.web.client.RestClientException}s.</p>
 */
public interface DocumentIndex {

    /**
     * The backend's name, also the name of its health component.
     *
     * @return {@code solr} or {@code elasticsearch}
     */
    String name();

    /**
     * What the documents are written to, for log messages.
     *
     * @return for example {@code Solr collection articulorium}
     */
    String target();

    /**
     * Where the documents are written to, for health details. Never contains credentials.
     *
     * @return for example the base URL and the collection
     */
    Map<String, Object> location();

    /**
     * Creates the collection or index unless it exists.
     *
     * @return {@code true} when it was created
     */
    boolean ensureIndex();

    /**
     * Declares the configured fields unless they exist.
     *
     * @return the names of the fields that were added
     * @throws org.springframework.web.client.HttpClientErrorException when the backend refuses a
     *         declaration (an unknown field type, for example)
     */
    List<String> ensureFields();

    /**
     * Adds or replaces a document.
     *
     * @param document the document; its {@code id} field identifies it
     */
    void add(Map<String, ?> document);

    /**
     * Deletes a document; deleting a document that is not indexed is not an error.
     *
     * @param id the document identifier
     */
    void deleteById(String id);

    /**
     * Checks that documents can be read.
     *
     * @return the backend's condition
     * @throws org.springframework.web.client.RestClientException when the backend cannot be reached
     *         or the collection or index does not exist
     */
    IndexStatus status();

}
