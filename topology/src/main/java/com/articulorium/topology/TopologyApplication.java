package com.articulorium.topology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorium topology service: definitions of PCDM structures and their
 * instantiation in the Linked Data Platform.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.articulorium.topology")
public class TopologyApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(TopologyApplication.class, args);
    }

}
