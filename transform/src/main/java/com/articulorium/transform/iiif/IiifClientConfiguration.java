package com.articulorium.transform.iiif;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client the IIIF export reads the platform with. With {@code articulorium.iiif.load-balanced}
 * (the default) it resolves Consul service names such as {@code http://articulorium-platform}.
 */
@Configuration(proxyBeanMethods = false)
public class IiifClientConfiguration {

    /**
     * A builder of clients that resolve Consul service names.
     *
     * @return the load-balanced builder
     */
    @Bean
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorium.iiif.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

}
