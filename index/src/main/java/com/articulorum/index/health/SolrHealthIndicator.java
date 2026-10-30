package com.articulorum.index.health;

import com.articulorum.index.config.SolrProperties;
import com.articulorum.index.solr.SolrIndexClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Reports whether the Solr collection the index service writes to is available.
 *
 * <p>The check calls the collection's ping handler and reports the number of indexed documents.
 * Reported as the {@code solr} health component; disable it with
 * {@code management.health.solr.enabled=false}.</p>
 */
@Component("solrHealthIndicator")
@ConditionalOnBooleanProperty(name = "management.health.solr.enabled", matchIfMissing = true)
public class SolrHealthIndicator extends AbstractHealthIndicator {

    /** Status the ping handler reports for a healthy collection. */
    static final String OK = "OK";

    private final SolrIndexClient solr;

    private final SolrProperties properties;

    /**
     * Creates the indicator.
     *
     * @param solr       the Solr client
     * @param properties Solr settings
     */
    public SolrHealthIndicator(final SolrIndexClient solr, final SolrProperties properties) {
        super("Solr health check failed");
        this.solr = solr;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        builder.withDetail("baseUrl", properties.getBaseUrl()).withDetail("collection", properties.getCollection());
        final String status = solr.ping();
        builder.withDetail("status", status);
        if (OK.equalsIgnoreCase(status)) {
            builder.withDetail("documents", solr.countDocuments()).up();
        } else {
            builder.down();
        }
    }

}
