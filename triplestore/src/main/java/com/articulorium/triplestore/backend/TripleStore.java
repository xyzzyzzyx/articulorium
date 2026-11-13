package com.articulorium.triplestore.backend;

import java.util.Map;

/**
 * The SPARQL store the service mirrors resources into: QLever by default, or Blazegraph or Apache
 * Jena Fuseki ({@code articulorium.triplestore.backend}).
 *
 * <p>Each LDP resource is kept in a named graph of its own, named by the resource's IRI.
 * Implementations talk to their store over HTTP and report failures as
 * {@link org.springframework.web.client.RestClientException}s.</p>
 */
public interface TripleStore {

    /**
     * The backend's name, also the name of its health component.
     *
     * @return {@code qlever}, {@code blazegraph} or {@code jena}
     */
    String name();

    /**
     * What the graphs are written to, for log messages.
     *
     * @return for example {@code QLever at http://qlever:7001/}
     */
    String target();

    /**
     * Where the graphs are written to, for health details. Never contains credentials.
     *
     * @return for example the SPARQL endpoint
     */
    Map<String, Object> location();

    /**
     * Creates what the store needs before graphs can be written, unless it exists. Stores that
     * need nothing do nothing.
     *
     * @return {@code true} when something was created
     */
    default boolean prepare() {
        return false;
    }

    /**
     * Replaces the content of a named graph (creating it when missing); an empty graph is dropped.
     *
     * @param graph    the graph IRI
     * @param nTriples the new content
     */
    void replaceGraph(String graph, String nTriples);

    /**
     * Drops a named graph; a missing graph is not an error.
     *
     * @param graph the graph IRI
     */
    void dropGraph(String graph);

    /**
     * Runs a SPARQL 1.1 Update.
     *
     * @param update the update
     */
    void update(String update);

    /**
     * Runs an ASK query.
     *
     * @param query the query
     * @return the answer
     */
    boolean ask(String query);

    /**
     * Counts the named graphs (one per mirrored resource).
     *
     * @return the number of graphs, or -1 when the answer cannot be read
     */
    long countGraphs();

    /**
     * Checks that the store answers.
     *
     * @return {@code true} when it does
     * @throws org.springframework.web.client.RestClientException when it does not
     */
    boolean ping();

}
