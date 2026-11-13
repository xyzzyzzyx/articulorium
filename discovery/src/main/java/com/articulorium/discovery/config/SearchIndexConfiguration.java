package com.articulorium.discovery.config;

import com.articulorium.discovery.elasticsearch.ElasticsearchSearchIndex;
import com.articulorium.discovery.index.SearchIndex;
import com.articulorium.discovery.solr.SolrSchema;
import com.articulorium.discovery.solr.SolrSearchClient;
import com.articulorium.discovery.solr.SolrSearchIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the search index backend ({@code articulorium.discovery.index.backend}): Solr unless
 * configured. Both are reached with plain HTTP clients: neither is registered in Consul.
 *
 * <p>The backend is chosen when the service starts, not with bean conditions: conditions are
 * evaluated when a GraalVM native image is built, which would fix the backend at build time.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SearchIndexConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SearchIndexConfiguration.class);

    /**
     * The search index of the configured backend.
     *
     * @param properties the settings
     * @return the index
     */
    @Bean
    public SearchIndex searchIndex(final DiscoveryProperties properties) {
        return switch (properties.getIndex().getBackend()) {
            case SOLR -> {
                LOG.info("Searching the Solr collection {} ({})", properties.getSolr().getCollection(),
                    properties.getSolr().getBaseUrl());
                final SolrSearchClient solr = new SolrSearchClient(properties);
                yield new SolrSearchIndex(solr, new SolrSchema(solr));
            }
            case ELASTICSEARCH -> {
                LOG.info("Searching the Elasticsearch index {} ({})", properties.getElasticsearch().getIndex(),
                    properties.getElasticsearch().getBaseUrl());
                yield new ElasticsearchSearchIndex(properties);
            }
        };
    }

}
