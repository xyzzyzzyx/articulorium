package com.articulorium.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorium discovery service: the configuration, search and resource API of
 * the public discovery and presentation layer (the server-rendered {@code discovery-ui}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.articulorium.discovery")
public class DiscoveryApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }

}
