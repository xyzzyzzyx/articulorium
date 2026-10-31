package com.articulorum.topology.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client towards the platform. With {@code articulorum.topology.load-balanced=true} (the
 * default) the client resolves Consul service names such as {@code http://articulorum-platform};
 * otherwise Spring Boot's plain builder is used.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClientConfiguration {

    /**
     * A builder of clients that resolve Consul service names.
     *
     * @return the load-balanced builder
     */
    @Bean
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorum.topology.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

}
