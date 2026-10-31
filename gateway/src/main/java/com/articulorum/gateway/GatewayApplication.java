package com.articulorum.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Articulorum gateway.
 *
 * <p>Routes are declared in {@code application.yml}; services are resolved through Consul
 * discovery and Spring Cloud LoadBalancer ({@code lb://} URIs).</p>
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * Starts the gateway.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

}
