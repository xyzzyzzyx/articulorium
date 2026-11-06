package com.articulorium.harvest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorium harvest service: harvests records from external repositories
 * (Fedora Commons, DSpace, VIVO, any OAI-PMH data provider) into the Linked Data Platform.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.articulorium.harvest")
public class HarvestApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(HarvestApplication.class, args);
    }

}
