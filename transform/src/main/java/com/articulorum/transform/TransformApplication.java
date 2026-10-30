package com.articulorum.transform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorum transform service.
 *
 * <p>The service consumes {@code ResourceEvent}s from the bus, turns each changed resource into a
 * search document with the configured {@link com.articulorum.transform.spi.Transformer}s and
 * publishes the result as a {@code DocumentEvent} for the index service.</p>
 */
@SpringBootApplication(scanBasePackages = "com.articulorum")
@ConfigurationPropertiesScan("com.articulorum.transform")
public class TransformApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(TransformApplication.class, args);
    }

}
