package com.articulorium.service;

import com.articulorium.agent.Agent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the Articulorium service: hosts the Angular user interface (with HTML5 routing
 * and base href templating) and the small REST API it uses next to the Linked Data Platform.
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

    /**
     * The agent that reads OWL ontologies.
     *
     * @return the agent
     */
    @Bean
    public Agent owlAgent() {
        return new Agent("owl", "read owl");
    }

}
