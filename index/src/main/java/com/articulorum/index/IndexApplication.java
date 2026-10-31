package com.articulorum.index;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorum index service.
 */
@SpringBootApplication(scanBasePackages = "com.articulorum")
@ConfigurationPropertiesScan("com.articulorum.index")
public class IndexApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(IndexApplication.class, args);
    }

}
