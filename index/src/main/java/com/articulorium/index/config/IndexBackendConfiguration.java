package com.articulorium.index.config;

import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.elasticsearch.ElasticsearchIndexClient;
import com.articulorium.index.health.IndexHealthIndicator;
import com.articulorium.index.solr.SolrIndexClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Selects the search index backend ({@code articulorium.index.backend}): Solr unless configured.
 *
 * <p>The backend is chosen when the service starts, not with bean conditions: conditions are
 * evaluated when a GraalVM native image is built, which would fix the backend at build time.</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class IndexBackendConfiguration {

    /**
     * The search index of the configured backend.
     *
     * @param properties    index settings
     * @param solr          Solr settings
     * @param elasticsearch Elasticsearch settings
     * @param builder       the auto-configured REST client builder
     * @return the index
     */
    @Bean
    public DocumentIndex documentIndex(final IndexProperties properties, final SolrProperties solr,
            final ElasticsearchProperties elasticsearch, final RestClient.Builder builder) {
        final DocumentIndex index = switch (properties.getBackend()) {
            case SOLR -> new SolrIndexClient(builder, solr);
            case ELASTICSEARCH -> new ElasticsearchIndexClient(builder, elasticsearch);
        };
        log.info("Indexing into {} ({})", index.target(), index.location().get("baseUrl"));
        return index;
    }

    /**
     * Registers the health component of the backend under its name ({@code solr} or
     * {@code elasticsearch}); disable it with {@code management.health.<name>.enabled=false}.
     *
     * @param registry    the health contributors, absent without the actuator's health support
     * @param index       the index
     * @param environment the environment
     * @return the registration, run once every bean exists
     */
    @Bean
    public SmartInitializingSingleton indexHealthRegistration(final ObjectProvider<HealthContributorRegistry> registry,
            final DocumentIndex index, final Environment environment) {
        return () -> {
            if (environment.getProperty("management.health." + index.name() + ".enabled", Boolean.class, true)) {
                registry.ifAvailable(contributors ->
                    contributors.registerContributor(index.name(), new IndexHealthIndicator(index)));
            }
        };
    }

}
