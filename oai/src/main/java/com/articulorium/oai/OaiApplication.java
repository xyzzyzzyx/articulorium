package com.articulorium.oai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorium OAI-PMH service: an OAI-PMH 2.0 data provider for the metadata
 * of the Linked Data Platform's resources.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.articulorium.oai")
public class OaiApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(OaiApplication.class, args);
    }

}
