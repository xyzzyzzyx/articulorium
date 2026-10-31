package com.articulorum.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point of the Articulorum Linked Data Platform service.
 *
 * <p>Component scanning covers {@code com.articulorum} so the shared bus configuration from the
 * event module is picked up. Entities come from the domain module and this module's namespace,
 * WebAC (access control lists) and notify (COAR Notify inboxes) packages; repositories live in
 * this module.</p>
 */
@SpringBootApplication(scanBasePackages = "com.articulorum")
@ConfigurationPropertiesScan("com.articulorum.platform")
@EntityScan(basePackages = { "com.articulorum.domain", "com.articulorum.platform.namespace",
    "com.articulorum.platform.webac", "com.articulorum.platform.notify" })
@EnableJpaRepositories(basePackages = { "com.articulorum.platform.repository", "com.articulorum.platform.namespace",
    "com.articulorum.platform.webac", "com.articulorum.platform.notify" })
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
