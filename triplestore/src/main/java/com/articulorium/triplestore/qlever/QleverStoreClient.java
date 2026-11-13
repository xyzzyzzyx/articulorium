package com.articulorium.triplestore.qlever;

import java.util.Map;

import com.articulorium.triplestore.config.TriplestoreProperties;
import com.articulorium.triplestore.sparql.SparqlStoreClient;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * <a href="https://github.com/ad-freiburg/qlever">QLever</a>, the default store
 * ({@code articulorium.triplestore.service-url}, {@code sparql-path}, {@code graph-store-path},
 * {@code access-token}).
 *
 * <p>QLever serves queries, updates and the Graph Store HTTP Protocol at the root of its HTTP port
 * and requires its access token, sent as {@code Authorization: Bearer}, for every change.</p>
 */
public class QleverStoreClient extends SparqlStoreClient {

    /** The backend's name. */
    public static final String NAME = "qlever";

    private final TriplestoreProperties properties;

    /**
     * Creates the client.
     *
     * @param builder    HTTP client builder
     * @param properties store settings
     */
    public QleverStoreClient(final RestClient.Builder builder, final TriplestoreProperties properties) {
        super(builder,
            new Endpoints(properties.sparqlEndpoint(), properties.sparqlEndpoint(), properties.graphStoreEndpoint()),
            headers -> {
                if (StringUtils.hasText(properties.getAccessToken())) {
                    headers.setBearerAuth(properties.getAccessToken());
                }
            });
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String target() {
        return "QLever at " + properties.sparqlEndpoint();
    }

    @Override
    public Map<String, Object> location() {
        return Map.of("endpoint", properties.sparqlEndpoint());
    }

    /**
     * Checks QLever's liveness probe ({@code GET /ping}).
     *
     * @return {@code true} when the probe succeeded
     */
    @Override
    public boolean ping() {
        final String url = properties.getServiceUrl();
        client().get()
            .uri((url.endsWith("/") ? url : url + "/") + "ping")
            .retrieve()
            .toBodilessEntity();
        return true;
    }

}
