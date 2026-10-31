package com.articulorum.triplestore.health;

import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Reports whether the SPARQL store (QLever) is reachable: its liveness probe answers and a query
 * counting the mirrored graphs succeeds. Disable with {@code management.health.qlever.enabled=false}.
 */
@Component("qleverHealthIndicator")
@ConditionalOnBooleanProperty(name = "management.health.qlever.enabled", matchIfMissing = true)
public class QleverHealthIndicator extends AbstractHealthIndicator {

    private final SparqlStoreClient store;

    private final TriplestoreProperties properties;

    /**
     * Creates the indicator.
     *
     * @param store      the SPARQL store client
     * @param properties store settings
     */
    public QleverHealthIndicator(final SparqlStoreClient store, final TriplestoreProperties properties) {
        super("SPARQL store health check failed");
        this.store = store;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        builder.withDetail("endpoint", properties.sparqlEndpoint());
        store.ping();
        builder.withDetail("graphs", store.countGraphs()).up();
    }

}
