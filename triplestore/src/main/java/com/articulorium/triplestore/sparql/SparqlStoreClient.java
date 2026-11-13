package com.articulorium.triplestore.sparql;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.articulorium.triplestore.backend.TripleStore;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client of a SPARQL store over the standard protocols, the base of the store implementations.
 *
 * <p>Graphs are replaced and dropped with the SPARQL 1.1 Graph Store HTTP Protocol
 * ({@code PUT}/{@code DELETE <endpoint>?graph=<iri>}); queries and updates use the SPARQL 1.1
 * Protocol. Subclasses name the endpoints and the authentication of their store and override what
 * it does differently.</p>
 */
public abstract class SparqlStoreClient implements TripleStore {

    /** Media type of SPARQL JSON results. */
    public static final MediaType SPARQL_RESULTS_JSON = MediaType.parseMediaType("application/sparql-results+json");

    /** Media type of SPARQL Update requests. */
    public static final MediaType SPARQL_UPDATE = MediaType.parseMediaType("application/sparql-update");

    /** Media type of graph uploads; N-Triples is a subset of Turtle. */
    public static final MediaType TURTLE = new MediaType("text", "turtle", StandardCharsets.UTF_8);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;

    private final Endpoints endpoints;

    /**
     * The endpoints of a store.
     *
     * @param query      SPARQL query endpoint
     * @param update     SPARQL update endpoint
     * @param graphStore Graph Store HTTP Protocol endpoint
     */
    public record Endpoints(String query, String update, String graphStore) {
    }

    /**
     * Creates the client.
     *
     * @param builder        HTTP client builder
     * @param endpoints      the store's endpoints
     * @param authentication adds the store's credentials to the headers of every request
     */
    protected SparqlStoreClient(final RestClient.Builder builder, final Endpoints endpoints,
            final Consumer<HttpHeaders> authentication) {
        this.endpoints = endpoints;
        this.client = builder.defaultHeaders(authentication).build();
    }

    /**
     * HTTP Basic authentication, when a user name is configured.
     *
     * @param username the user name, may be empty
     * @param password the password, may be empty
     * @return the header customizer
     */
    protected static Consumer<HttpHeaders> basicAuthentication(final String username, final String password) {
        return headers -> {
            if (StringUtils.hasText(username)) {
                headers.setBasicAuth(username, password == null ? "" : password, StandardCharsets.UTF_8);
            }
        };
    }

    /**
     * The HTTP client, carrying the store's credentials.
     *
     * @return the client
     */
    protected RestClient client() {
        return client;
    }

    /**
     * The store's endpoints.
     *
     * @return the endpoints
     */
    protected Endpoints endpoints() {
        return endpoints;
    }

    @Override
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

    @Override
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

    @Override
    public void update(final String update) {
        client.post()
            .uri(endpoints.update())
            .contentType(SPARQL_UPDATE)
            .body(update)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public boolean ask(final String query) {
        final Map<String, Object> response = query(query);
        return response != null && Boolean.TRUE.equals(response.get("boolean"));
    }

    @Override
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
     * Checks that the store answers a query; stores with a liveness probe override this.
     *
     * @return {@code true} when the query was answered
     */
    @Override
    public boolean ping() {
        query("ASK { ?s ?p ?o }");
        return true;
    }

    private Map<String, Object> query(final String query) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", query);
        return client.post()
            .uri(endpoints.query())
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
    public URI graphUri(final String graph) {
        return UriComponentsBuilder.fromUriString(endpoints.graphStore())
            .queryParam("graph", "{graph}")
            .encode()
            .buildAndExpand(graph)
            .toUri();
    }

}
