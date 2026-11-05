package com.articulorium.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point of the Articulorium Linked Data Platform service.
 *
 * <p>Component scanning covers {@code com.articulorium} so the shared bus configuration from the
 * event module is picked up. Entities come from the domain module and this module's namespace,
 * WebAC (access control lists) and notify (COAR Notify inboxes) packages; repositories live in
 * this module.</p>
 */
@SpringBootApplication(scanBasePackages = "com.articulorium")
@ConfigurationPropertiesScan("com.articulorium.platform")
@EntityScan(basePackages = { "com.articulorium.domain", "com.articulorium.platform.namespace",
    "com.articulorium.platform.webac", "com.articulorium.platform.notify" })
@EnableJpaRepositories(basePackages = { "com.articulorium.platform.repository", "com.articulorium.platform.namespace",
    "com.articulorium.platform.webac", "com.articulorium.platform.notify" })
public class LdpApplication {

    /**
     * Starts the service.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(LdpApplication.class, args);
    }

}
