package com.articulorium.index.health;

import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.backend.IndexStatus;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the search index the service writes to is available.
 *
 * <p>The check asks the backend for its condition and reports the number of indexed documents.
 * Registered as the health component named after the backend ({@code solr} or
 * {@code elasticsearch}, see
 * {@link com.articulorium.index.config.IndexBackendConfiguration}); disable it with
 * {@code management.health.<backend>.enabled=false}.</p>
 */
public class IndexHealthIndicator extends AbstractHealthIndicator {

    private final DocumentIndex index;

    /**
     * Creates the indicator.
     *
     * @param index the search index
     */
    public IndexHealthIndicator(final DocumentIndex index) {
        super("Search index health check failed");
        this.index = index;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        index.location().forEach(builder::withDetail);
        final IndexStatus status = index.status();
        builder.withDetail("status", status.status());
        if (status.available()) {
            builder.withDetail("documents", status.documents()).up();
        } else {
            builder.down();
        }
    }

}
