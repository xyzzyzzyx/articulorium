package com.articulorium.index.listener;

import java.time.Duration;
import java.util.List;

import com.articulorium.index.config.SolrProperties;
import com.articulorium.index.solr.SolrIndexClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the Solr collection once the application is ready, if it does not exist yet.
 *
 * <p>Solr may still be starting when this service is ready, so creation is retried in the
 * background (every {@value #RETRY_DELAY_SECONDS}s, at most {@value #MAX_ATTEMPTS} times) without
 * delaying start-up.</p>
 */
@Slf4j
@Component
public class SolrCollectionInitializer implements ApplicationListener<ApplicationReadyEvent> {

    /** Maximum number of attempts. */
    static final int MAX_ATTEMPTS = 60;

    /** Delay between attempts in seconds. */
    static final int RETRY_DELAY_SECONDS = 5;

    private final SolrIndexClient solr;

    private final SolrProperties properties;

    /**
     * Creates the initializer.
     *
     * @param solr       the Solr client
     * @param properties Solr settings
     */
    public SolrCollectionInitializer(final SolrIndexClient solr, final SolrProperties properties) {
        this.solr = solr;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        Thread.ofVirtual().name("solr-collection-initializer").start(() -> initialize(MAX_ATTEMPTS, Duration.ofSeconds(RETRY_DELAY_SECONDS)));
    }

    /**
     * Tries to prepare the collection until it succeeds or the attempts are exhausted.
     *
     * @param attempts maximum number of attempts
     * @param delay    delay between attempts
     * @return {@code true} when the collection is available
     */
    boolean initialize(final int attempts, final Duration delay) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (solr.ensureCollection()) {
                    log.info("Created Solr collection {}", properties.getCollection());
                } else {
                    log.info("Solr collection {} is available", properties.getCollection());
                }
                declareFields();
                return true;
            } catch (RestClientException e) {
                log.info("Solr not ready (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                if (!sleep(delay)) {
                    return false;
                }
            }
        }
        log.warn("Giving up preparing Solr collection {}", properties.getCollection());
        return false;
    }

    /**
     * Declares the configured fields. Solr refusing a declaration (an unknown field type, for
     * example) is a configuration problem that retrying does not solve: it is logged, and the
     * collection is used as it is.
     */
    private void declareFields() {
        try {
            final List<String> added = solr.ensureFields(properties.getFields());
            if (!added.isEmpty()) {
                log.info("Declared fields {} in Solr collection {}", added, properties.getCollection());
            }
        } catch (HttpClientErrorException e) {
            log.warn("Solr refused to declare the fields {} in collection {}: {}", properties.getFields().keySet(),
                properties.getCollection(), e.getMessage());
        }
    }

    private static boolean sleep(final Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
