package com.articulorum.triplestore.sparql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.triplestore.config.TriplestoreProperties;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Writes resources to a SPARQL 1.1 store, one named graph per resource.
 *
 * <p>Updates use the standard SPARQL 1.1 Protocol ({@code update=} form parameter), so they work
 * with any compliant store. Namespace creation uses Blazegraph's REST API and is optional.</p>
 */
@Component
public class SparqlStoreClient {

    /** Media type of SPARQL 1.1 JSON query results. */
    static final MediaType SPARQL_RESULTS_JSON = MediaType.parseMediaType("application/sparql-results+json");

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;

    private final TriplestoreProperties properties;

    /**
     * Creates the client.
     *
     * @param builder    the auto-configured REST client builder
     * @param properties store settings
     */
    public SparqlStoreClient(final RestClient.Builder builder, final TriplestoreProperties properties) {
        this.client = builder.build();
        this.properties = properties;
    }

    /**
     * Creates the Blazegraph namespace in quads mode unless it exists.
     *
     * @return {@code true} when the namespace was created
     */
    public boolean ensureNamespace() {
        try {
            client.get()
                .uri(properties.namespacesEndpoint() + "/" + properties.getNamespace() + "/properties")
                .retrieve()
                .toBodilessEntity();
            return false;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw e;
            }
        }
        client.post()
            .uri(properties.namespacesEndpoint())
            .contentType(MediaType.APPLICATION_XML)
            .body(namespaceProperties(properties.getNamespace()))
            .retrieve()
            .toBodilessEntity();
        return true;
    }

    /**
     * Replaces the content of a named graph.
     *
     * @param graph    the graph IRI
     * @param nTriples the new content as N-Triples (absolute IRIs)
     */
    public void replaceGraph(final String graph, final String nTriples) {
        update(replaceGraphUpdate(graph, nTriples));
    }

    /**
     * Removes a named graph.
     *
     * @param graph the graph IRI
     */
    public void dropGraph(final String graph) {
        update(dropGraphUpdate(graph));
    }

    /**
     * Sends a SPARQL 1.1 Update.
     *
     * @param update the update request
     */
    public void update(final String update) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("update", update);
        client.post()
            .uri(properties.sparqlEndpoint())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * Builds the update replacing a graph: drop it, then insert the new triples.
     *
     * @param graph    the graph IRI
     * @param nTriples the content as N-Triples
     * @return the update request
     */
    /**
     * Runs a SPARQL ASK query against the namespace's endpoint.
     *
     * @param query the ASK query
     * @return the boolean result
     * @throws org.springframework.web.client.RestClientException when the store cannot be reached
     *         or the namespace does not exist
     */
    public boolean ask(final String query) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", query);
        final Map<String, Object> response = client.post()
            .uri(properties.sparqlEndpoint())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(SPARQL_RESULTS_JSON, MediaType.APPLICATION_JSON)
            .body(form)
            .retrieve()
            .body(JSON_OBJECT);
        return response != null && Boolean.TRUE.equals(response.get("boolean"));
    }

    /**
     * Counts the named graphs (one per mirrored resource) of the namespace.
     *
     * @return the number of graphs, or {@code -1} when the store returned no count
     */
    @SuppressWarnings("unchecked")
    public long countGraphs() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", "SELECT (COUNT(DISTINCT ?g) AS ?graphs) WHERE { GRAPH ?g { ?s ?p ?o } }");
        final Map<String, Object> response = client.post()
            .uri(properties.sparqlEndpoint())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(SPARQL_RESULTS_JSON, MediaType.APPLICATION_JSON)
            .body(form)
            .retrieve()
            .body(JSON_OBJECT);
        try {
            final Map<String, Object> results = (Map<String, Object>) response.get("results");
            final List<Map<String, Object>> bindings = (List<Map<String, Object>>) results.get("bindings");
            final Map<String, Object> graphs = (Map<String, Object>) bindings.get(0).get("graphs");
            return Long.parseLong(String.valueOf(graphs.get("value")));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String replaceGraphUpdate(final String graph, final String nTriples) {
        final String target = NTriplesSerializer.iri(graph);
        final StringBuilder update = new StringBuilder("DROP SILENT GRAPH ").append(target).append(" ;\n");
        if (!nTriples.isBlank()) {
            update.append("INSERT DATA { GRAPH ").append(target).append(" {\n").append(nTriples).append("} }");
        }
        return update.toString();
    }

    /**
     * Builds the update removing a graph.
     *
     * @param graph the graph IRI
     * @return the update request
     */
    static String dropGraphUpdate(final String graph) {
        return "DROP SILENT GRAPH " + NTriplesSerializer.iri(graph);
    }

    /**
     * Serialises the Blazegraph properties of a quads-mode namespace as a Java properties XML
     * document, the format Blazegraph's namespace API accepts.
     *
     * @param namespace the namespace name
     * @return the XML document
     */
    static String namespaceProperties(final String namespace) {
        final Properties settings = new Properties();
        settings.setProperty("com.bigdata.rdf.sail.namespace", namespace);
        settings.setProperty("com.bigdata.rdf.store.AbstractTripleStore.quads", "true");
        settings.setProperty("com.bigdata.rdf.store.AbstractTripleStore.statementIdentifiers", "false");
        settings.setProperty("com.bigdata.rdf.sail.truthMaintenance", "false");
        settings.setProperty("com.bigdata.rdf.store.AbstractTripleStore.axiomsClass", "com.bigdata.rdf.axioms.NoAxioms");
        settings.setProperty("com.bigdata.rdf.store.AbstractTripleStore.textIndex", "false");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            settings.storeToXML(out, "Articulorum namespace", StandardCharsets.UTF_8);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
