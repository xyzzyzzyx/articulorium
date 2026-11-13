package com.articulorium.discovery.index;

/**
 * The search index the discovery layer reads: the Solr collection or Elasticsearch index the index
 * service keeps in sync ({@code articulorium.discovery.index.backend}, Solr unless configured).
 *
 * <p>Searches are described once, as {@link IndexQuery}s, and each backend renders them in its own
 * query language. Documents have the same fields in both (see {@code README.md}, "Search index").</p>
 */
public interface SearchIndex {

    /**
     * Runs a query.
     *
     * @param query the query
     * @return the matching documents and facet counts
     * @throws IndexUnavailableException when the index cannot be reached or refuses the query
     */
    IndexResult search(IndexQuery query);

    /**
     * Tells whether a field can be named in a search.
     *
     * @param field the field name
     * @return {@code false} when naming the field would fail the whole search (Solr refuses
     *     searches naming fields its collection does not have yet)
     */
    boolean hasField(String field);

}
