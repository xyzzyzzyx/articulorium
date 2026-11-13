package com.articulorium.discovery.index;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The answer of a search index.
 *
 * @param total     the number of matching documents
 * @param documents the requested page of documents: objects of field names and values (a single
 *     value or an array of values)
 * @param facets    the counts of the requested facets, by facet identifier
 */
public record IndexResult(long total, List<JsonNode> documents, Map<String, List<Count>> facets) {

    /**
     * An answer without documents.
     *
     * @return the empty answer
     */
    public static IndexResult empty() {
        return new IndexResult(0, List.of(), Map.of());
    }

    /**
     * The counts of a facet.
     *
     * @param facet the facet identifier
     * @return its counts in the backend's order, empty when it has none
     */
    public List<Count> facet(final String facet) {
        return facets.getOrDefault(facet, List.of());
    }

    /**
     * How many matching documents have a facet value.
     *
     * @param value the value; for year facets the start of the range ({@code 1850-01-01T00:00:00Z})
     * @param count the number of documents
     */
    public record Count(String value, long count) {
    }

}
