package com.articulorium.triplestore.health;

import com.articulorium.triplestore.backend.TripleStore;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the SPARQL store is reachable: it answers and a query counting the mirrored
 * graphs succeeds.
 *
 * <p>Registered as the health component named after the backend ({@code qlever},
 * {@code blazegraph} or {@code jena}, see
 * {@link com.articulorium.triplestore.config.TriplestoreBackendConfiguration}); disable it with
 * {@code management.health.<backend>.enabled=false}.</p>
 */
public class TriplestoreHealthIndicator extends AbstractHealthIndicator {

    private final TripleStore store;

    /**
     * Creates the indicator.
     *
     * @param store the SPARQL store
     */
    public TriplestoreHealthIndicator(final TripleStore store) {
        super("SPARQL store health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        store.location().forEach(builder::withDetail);
        store.ping();
        builder.withDetail("graphs", store.countGraphs()).up();
    }

}
