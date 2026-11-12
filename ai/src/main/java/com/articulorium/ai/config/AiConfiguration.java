package com.articulorium.ai.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
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
 *
 * <p>The builder of the service clients is injected by its qualifier ({@link #SERVICES}) only and
 * is not a default candidate. Spring Boot's own {@link RestClient.Builder} therefore stays in
 * place for everything else, in particular for Spring AI's model providers: Ollama is addressed by
 * its URL and is not a Consul service, so its requests must never pass the load balancer (which
 * would answer "no instances available for ollama").</p>
 */
@Configuration(proxyBeanMethods = false)
public class AiConfiguration {

    /** Qualifier of the builder of clients towards the platform and the topology service. */
    public static final String SERVICES = "aiServices";

    /**
     * A builder of clients that resolve Consul service names such as {@code http://articulorium-platform}.
     *
     * @return the load-balanced builder
     */
    @Bean(name = SERVICES, defaultCandidate = false)
    @Qualifier(SERVICES)
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorium.ai.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * A builder of plain clients, when the service names are host names.
     *
     * @return the builder
     */
    @Bean(name = SERVICES, defaultCandidate = false)
    @Qualifier(SERVICES)
    @ConditionalOnBooleanProperty(name = "articulorium.ai.load-balanced", havingValue = false)
    public RestClient.Builder plainRestClientBuilder() {
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
