package com.articulorium.discovery.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients towards the platform and the transform service. With
 * {@code articulorium.discovery.load-balanced=true} (the default) they resolve Consul service names
 * such as {@code http://articulorium-platform}. Solr is reached with a plain client
 * ({@link com.articulorium.discovery.solr.SolrSearchClient}): it is not registered in Consul.
 */
@Configuration(proxyBeanMethods = false)
public class DiscoveryClientConfiguration {

    /** Qualifier of the builder of service clients. */
    public static final String SERVICES = "discoveryServices";

    /**
     * A builder of clients that resolve Consul service names.
     *
     * @return the load-balanced builder
     */
    @Bean(SERVICES)
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorium.discovery.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * A builder of plain clients, when service names are host names.
     *
     * @return the builder
     */
    @Bean(SERVICES)
    @ConditionalOnBooleanProperty(name = "articulorium.discovery.load-balanced", havingValue = false)
    public RestClient.Builder plainRestClientBuilder() {
        return RestClient.builder();
    }

}
