package com.articulorum.triplestore.listener;

import java.time.Duration;

import com.articulorum.triplestore.config.TriplestoreProperties;
import com.articulorum.triplestore.sparql.SparqlStoreClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the Blazegraph namespace once the application is ready, when enabled and missing.
 *
 * <p>Blazegraph may still be starting when this service is ready, so creation is retried in the
 * background (every {@value #RETRY_DELAY_SECONDS}s, at most {@value #MAX_ATTEMPTS} times) without
 * delaying start-up.</p>
 */
@Slf4j
@Component
public class NamespaceInitializer implements ApplicationListener<ApplicationReadyEvent> {

    /** Maximum number of attempts. */
    static final int MAX_ATTEMPTS = 60;

    /** Delay between attempts in seconds. */
    static final int RETRY_DELAY_SECONDS = 5;

    private final SparqlStoreClient store;

    private final TriplestoreProperties properties;

    /**
     * Creates the initializer.
     *
     * @param store      the SPARQL store client
     * @param properties store settings
     */
    public NamespaceInitializer(final SparqlStoreClient store, final TriplestoreProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        if (properties.isCreateNamespace()) {
            Thread.ofVirtual().name("triplestore-namespace-initializer")
                .start(() -> initialize(MAX_ATTEMPTS, Duration.ofSeconds(RETRY_DELAY_SECONDS)));
        }
    }

    /**
     * Tries to prepare the namespace until it succeeds or the attempts are exhausted.
     *
     * @param attempts maximum number of attempts
     * @param delay    delay between attempts
     * @return {@code true} when the namespace is available
     */
    boolean initialize(final int attempts, final Duration delay) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (store.ensureNamespace()) {
                    log.info("Created triplestore namespace {}", properties.getNamespace());
                } else {
                    log.info("Triplestore namespace {} is available", properties.getNamespace());
                }
                return true;
            } catch (RestClientException e) {
                log.info("Triplestore not ready (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                if (!sleep(delay)) {
                    return false;
                }
            }
        }
        log.warn("Giving up preparing triplestore namespace {}", properties.getNamespace());
        return false;
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
