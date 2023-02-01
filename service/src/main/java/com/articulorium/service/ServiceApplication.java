package com.articulorium.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Articulorium service: hosts the Angular user interface (with HTML5 routing
 * and base href templating) and the small REST API it uses next to the Linked Data Platform.
 *
 * <p>Agents come from {@code articulorium.agents.definitions} and from {@code Agent} beans, which
 * the agent Spring Boot starter collects into an {@code AgentRegistry}.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.articulorium.service")
public class ServiceApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }

}
