package com.articulorum.triplestore.health;

import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Reports whether the SPARQL store (Blazegraph by default) mirroring the platform answers
 * queries.
 *
 * <p>The check runs {@code ASK {}} against the namespace's SPARQL endpoint, which succeeds only
 * when the store is up and the namespace exists, and reports the number of mirrored resources
 * (named graphs). Reported as the {@code blazegraph} health component; disable it with
 * {@code management.health.blazegraph.enabled=false}.</p>
 */
@Component("blazegraphHealthIndicator")
@ConditionalOnBooleanProperty(name = "management.health.blazegraph.enabled", matchIfMissing = true)
public class BlazegraphHealthIndicator extends AbstractHealthIndicator {

    private final SparqlStoreClient store;

    private final TriplestoreProperties properties;

    /**
     * Creates the indicator.
     *
     * @param store      the SPARQL store client
     * @param properties store settings
     */
    public BlazegraphHealthIndicator(final SparqlStoreClient store, final TriplestoreProperties properties) {
        super("SPARQL store health check failed");
        this.store = store;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        builder.withDetail("endpoint", properties.sparqlEndpoint())
            .withDetail("namespace", properties.getNamespace());
        store.ask("ASK {}");
        builder.withDetail("graphs", store.countGraphs()).up();
    }

}
