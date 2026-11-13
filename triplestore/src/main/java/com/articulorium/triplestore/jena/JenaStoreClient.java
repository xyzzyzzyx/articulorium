package com.articulorium.triplestore.jena;

import java.util.LinkedHashMap;
import java.util.Map;

import com.articulorium.triplestore.config.TriplestoreProperties;
import com.articulorium.triplestore.sparql.SparqlStoreClient;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * A dataset of <a href="https://jena.apache.org/documentation/fuseki2/">Apache Jena Fuseki</a>
 * ({@code articulorium.triplestore.jena.*}).
 *
 * <p>Fuseki implements the standard protocols on separate services of the dataset:
 * {@code /<dataset>/sparql} (query), {@code /<dataset>/update} and {@code /<dataset>/data} (Graph
 * Store HTTP Protocol). The dataset must exist and allow updates; a user name and password are
 * sent with HTTP Basic authentication when configured.</p>
 */
public class JenaStoreClient extends SparqlStoreClient {

    /** The backend's name. */
    public static final String NAME = "jena";

    private final TriplestoreProperties.Jena properties;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder
     * @param properties Fuseki settings
     */
    public JenaStoreClient(final RestClient.Builder builder, final TriplestoreProperties.Jena properties) {
        super(builder, endpoints(properties), basicAuthentication(properties.getUsername(), properties.getPassword()));
        this.properties = properties;
    }

    private static Endpoints endpoints(final TriplestoreProperties.Jena properties) {
        Assert.hasText(properties.getServiceUrl(), "articulorium.triplestore.jena.service-url must be set");
        Assert.hasText(properties.getDataset(), "articulorium.triplestore.jena.dataset must be set");
        return new Endpoints(properties.queryEndpoint(), properties.updateEndpoint(), properties.graphStoreEndpoint());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String target() {
        return "Apache Jena Fuseki dataset " + properties.datasetUrl();
    }

    @Override
    public Map<String, Object> location() {
        final Map<String, Object> location = new LinkedHashMap<>();
        location.put("endpoint", properties.queryEndpoint());
        location.put("dataset", properties.getDataset());
        return location;
    }

}
