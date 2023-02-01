package com.articulorium.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.articulorium.ai.config.AiProperties;

/**
 * The AI agent service: suggestions about repository resources from a Spring AI chat model.
 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class AiApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }

}
