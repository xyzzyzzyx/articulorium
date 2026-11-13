package com.articulorium.triplestore.blazegraph;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.articulorium.triplestore.config.TriplestoreProperties;
import com.articulorium.triplestore.sparql.SparqlStoreClient;

import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * A namespace of <a href="https://github.com/blazegraph/database">Blazegraph</a>
 * ({@code articulorium.triplestore.blazegraph.*}).
 *
 * <p>Blazegraph serves queries and updates of a namespace at
 * {@code <service-url>/namespace/<namespace>/sparql}. It does not implement the Graph Store HTTP
 * Protocol, so a graph is replaced with two requests to that endpoint: a {@code DROP SILENT GRAPH}
 * update, then a {@code POST} of the new content with the graph as {@code context-uri}. The content
 * is always sent as an RDF document, never inside a SPARQL update, so nothing in a resource can
 * change the update. The two requests are not one transaction: when the second fails, the graph
 * stays empty until the resource changes again.</p>
 *
 * <p>Named graphs need a namespace in quads mode: a missing namespace is created that way
 * ({@link #prepare()}), an existing one is used as it is. A user name and password are sent with
 * HTTP Basic authentication when configured.</p>
 */
@Slf4j
public class BlazegraphStoreClient extends SparqlStoreClient {

    /** The backend's name. */
    public static final String NAME = "blazegraph";

    /** Namespace property naming the namespace. */
    static final String NAMESPACE_PROPERTY = "com.bigdata.rdf.sail.namespace";

    /** Namespace property turning on named graphs. */
    static final String QUADS_PROPERTY = "com.bigdata.rdf.store.AbstractTripleStore.quads";

    private final TriplestoreProperties.Blazegraph properties;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder
     * @param properties Blazegraph settings
     */
    public BlazegraphStoreClient(final RestClient.Builder builder, final TriplestoreProperties.Blazegraph properties) {
        super(builder, endpoints(properties), basicAuthentication(properties.getUsername(), properties.getPassword()));
        this.properties = properties;
    }

    private static Endpoints endpoints(final TriplestoreProperties.Blazegraph properties) {
        Assert.hasText(properties.getServiceUrl(), "articulorium.triplestore.blazegraph.service-url must be set");
        Assert.hasText(properties.getNamespace(), "articulorium.triplestore.blazegraph.namespace must be set");
        final String sparql = properties.sparqlEndpoint();
        return new Endpoints(sparql, sparql, sparql);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String target() {
        return "Blazegraph namespace " + properties.getNamespace() + " at " + properties.getServiceUrl();
    }

    @Override
    public Map<String, Object> location() {
        final Map<String, Object> location = new LinkedHashMap<>();
        location.put("endpoint", properties.sparqlEndpoint());
        location.put("namespace", properties.getNamespace());
        return location;
    }

    /**
     * Creates the namespace in quads mode unless it exists. An existing namespace that is not in
     * quads mode cannot hold named graphs: that is reported, and the namespace is left alone.
     *
     * @return {@code true} when the namespace was created
     */
    @Override
    public boolean prepare() {
        try {
            client().post()
                .uri(properties.namespacesEndpoint())
                .contentType(MediaType.TEXT_PLAIN)
                .body(namespaceProperties(properties.getNamespace()))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict e) {
            checkQuadsMode();
            return false;
        }
    }

    /**
     * The properties a namespace holding named graphs is created with: quads mode, which excludes
     * inference and statement identifiers.
     *
     * @param namespace the namespace
     * @return the properties in {@code .properties} syntax
     */
    static String namespaceProperties(final String namespace) {
        return NAMESPACE_PROPERTY + "=" + namespace + "\n"
            + QUADS_PROPERTY + "=true\n"
            + "com.bigdata.rdf.store.AbstractTripleStore.axiomsClass=com.bigdata.rdf.axioms.NoAxioms\n"
            + "com.bigdata.rdf.store.AbstractTripleStore.statementIdentifiers=false\n"
            + "com.bigdata.rdf.store.AbstractTripleStore.justify=false\n"
            + "com.bigdata.rdf.store.AbstractTripleStore.textIndex=false\n"
            + "com.bigdata.rdf.sail.truthMaintenance=false\n";
    }

    private void checkQuadsMode() {
        try {
            final String body = client().get()
                .uri(properties.namespacePropertiesEndpoint())
                .accept(MediaType.TEXT_PLAIN)
                .retrieve()
                .body(String.class);
            final Properties namespace = new Properties();
            namespace.load(new StringReader(body == null ? "" : body));
            if (!Boolean.parseBoolean(namespace.getProperty(QUADS_PROPERTY, "false").trim())) {
                log.error("Blazegraph namespace {} is not in quads mode ({}=true) and cannot hold the named graphs of "
                    + "resources: configure another namespace, which is created in quads mode",
                    properties.getNamespace(), QUADS_PROPERTY);
            }
        } catch (HttpClientErrorException | IOException e) {
            log.warn("Unable to read the properties of Blazegraph namespace {}: {}", properties.getNamespace(),
                e.getMessage());
        }
    }

    @Override
    public void replaceGraph(final String graph, final String nTriples) {
        dropGraph(graph);
        if (nTriples == null || nTriples.isBlank()) {
            return;
        }
        client().post()
            .uri(UriComponentsBuilder.fromUriString(properties.sparqlEndpoint())
                .queryParam("context-uri", "{graph}")
                .encode()
                .buildAndExpand(graphName(graph))
                .toUri())
            .contentType(TURTLE)
            .body(nTriples)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void dropGraph(final String graph) {
        update("DROP SILENT GRAPH <" + graphName(graph) + ">");
    }

    /**
     * The name a graph is stored under: its IRI, with the characters that cannot appear in an IRI
     * reference percent-encoded. Such characters would otherwise end the IRI inside the
     * {@code DROP} update.
     *
     * @param graph the graph IRI
     * @return the IRI, safe between angle brackets
     */
    static String graphName(final String graph) {
        final StringBuilder name = new StringBuilder(graph.length());
        for (int i = 0; i < graph.length(); i++) {
            final char c = graph.charAt(i);
            if (c <= 0x20 || "<>\"{}|^`\\".indexOf(c) >= 0) {
                name.append(String.format("%%%02X", (int) c));
            } else {
                name.append(c);
            }
        }
        return name.toString();
    }

}
