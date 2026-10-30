package com.articulorum.triplestore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorum triplestore service.
 */
@SpringBootApplication(scanBasePackages = "com.articulorum")
@ConfigurationPropertiesScan("com.articulorum.triplestore")
public class TriplestoreApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(TriplestoreApplication.class, args);
    }

}
