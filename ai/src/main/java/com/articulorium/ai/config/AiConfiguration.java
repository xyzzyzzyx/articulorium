package com.articulorium.ai.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the agent: HTTP clients towards the platform and the topology service (load balanced
 * unless {@code articulorium.ai.load-balanced=false}) and the clock. The capabilities register
 * themselves as Articulorium agents ({@link com.articulorium.ai.CapabilityAgent}), which the agent
 * starter collects into the agent registry.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

    /**
     * A builder of clients that resolve Consul service names such as {@code http://articulorium-platform}.
     *
     * @return the load-balanced builder
     */
    @Bean
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorium.ai.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * The clock.
     *
     * @return the system clock in UTC
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

}
