package com.articulorum.triplestore.sparql;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.articulorum.triplestore.config.TriplestoreProperties;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client of the SPARQL store (QLever by default).
 *
 * <p>Each LDP resource is kept in a named graph of its own, named by the resource's IRI. Graphs
 * are replaced and dropped with the SPARQL 1.1 Graph Store HTTP Protocol
 * ({@code PUT}/{@code DELETE <endpoint>?graph=<iri>}); queries use the SPARQL 1.1 Protocol. The
 * configured access token is sent as {@code Authorization: Bearer} with every request.</p>
 */
@Component
public class SparqlStoreClient {

    /** Media type of SPARQL JSON results. */
    static final MediaType SPARQL_RESULTS_JSON = MediaType.parseMediaType("application/sparql-results+json");

    /** Media type of SPARQL Update requests. */
    static final MediaType SPARQL_UPDATE = MediaType.parseMediaType("application/sparql-update");

    /** Media type of graph uploads; N-Triples is a subset of Turtle. */
    static final MediaType TURTLE = new MediaType("text", "turtle", StandardCharsets.UTF_8);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;

    private final TriplestoreProperties properties;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder
     * @param properties store settings
     */
    public SparqlStoreClient(final RestClient.Builder builder, final TriplestoreProperties properties) {
        this.properties = properties;
        this.client = builder
            .defaultHeaders(headers -> {
                if (StringUtils.hasText(properties.getAccessToken())) {
                    headers.setBearerAuth(properties.getAccessToken());
                }
            })
            .build();
    }

    /**
     * Replaces the content of a named graph (creating it when missing); an empty graph is dropped.
     *
     * @param graph    the graph IRI
     * @param nTriples the new content
     */
    public void replaceGraph(final String graph, final String nTriples) {
        if (nTriples == null || nTriples.isBlank()) {
            dropGraph(graph);
            return;
        }
        client.put()
            .uri(graphUri(graph))
            .contentType(TURTLE)
            .body(nTriples)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * Drops a named graph; a missing graph is not an error.
     *
     * @param graph the graph IRI
     */
    public void dropGraph(final String graph) {
        try {
            client.delete()
                .uri(graphUri(graph))
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // already gone
        }
    }

    /**
     * Runs a SPARQL 1.1 Update.
     *
     * @param update the update
     */
    public void update(final String update) {
        client.post()
            .uri(properties.sparqlEndpoint())
            .contentType(SPARQL_UPDATE)
            .body(update)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * Runs an ASK query.
     *
     * @param query the query
     * @return the answer
     */
    public boolean ask(final String query) {
        final Map<String, Object> response = query(query);
        return response != null && Boolean.TRUE.equals(response.get("boolean"));
    }

    /**
     * Counts the named graphs (one per mirrored resource).
     *
     * @return the number of graphs, or -1 when the answer cannot be read
     */
    @SuppressWarnings("unchecked")
    public long countGraphs() {
        final Map<String, Object> response = query("SELECT (COUNT(DISTINCT ?g) AS ?graphs) WHERE { GRAPH ?g { ?s ?p ?o } }");
        try {
            final Map<String, Object> results = (Map<String, Object>) response.get("results");
            final List<Map<String, Object>> bindings = (List<Map<String, Object>>) results.get("bindings");
            final Map<String, Object> graphs = (Map<String, Object>) bindings.get(0).get("graphs");
            return Long.parseLong(String.valueOf(graphs.get("value")));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Checks that the store answers its liveness probe ({@code GET /ping} on QLever).
     *
     * @return {@code true} when the probe succeeded
     */
    public boolean ping() {
        client.get()
            .uri(join(properties.getServiceUrl(), "ping"))
            .retrieve()
            .toBodilessEntity();
        return true;
    }

    private Map<String, Object> query(final String query) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", query);
        return client.post()
            .uri(properties.sparqlEndpoint())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(SPARQL_RESULTS_JSON, MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.name())
            .body(form)
            .retrieve()
            .body(JSON_OBJECT);
    }

    /**
     * Builds the Graph Store Protocol URI of a graph.
     *
     * @param graph the graph IRI
     * @return {@code <endpoint>?graph=<encoded iri>}
     */
    URI graphUri(final String graph) {
        return UriComponentsBuilder.fromUriString(properties.graphStoreEndpoint())
            .queryParam("graph", "{graph}")
            .encode()
            .buildAndExpand(graph)
            .toUri();
    }

    private static String join(final String url, final String path) {
        return (url.endsWith("/") ? url : url + "/") + path;
    }

}
