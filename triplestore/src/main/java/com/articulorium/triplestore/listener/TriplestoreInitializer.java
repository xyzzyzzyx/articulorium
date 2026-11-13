package com.articulorium.triplestore.listener;

import java.time.Duration;

import com.articulorium.triplestore.backend.TripleStore;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Prepares the SPARQL store once the application is ready: creates what the backend needs before
 * graphs can be written (the Blazegraph namespace), if it does not exist yet.
 *
 * <p>The store may still be starting when this service is ready, so preparation is retried in the
 * background (every {@value #RETRY_DELAY_SECONDS}s, at most {@value #MAX_ATTEMPTS} times) without
 * delaying start-up.</p>
 */
@Slf4j
@Component
public class TriplestoreInitializer implements ApplicationListener<ApplicationReadyEvent> {

    /** Maximum number of attempts. */
    static final int MAX_ATTEMPTS = 60;

    /** Delay between attempts in seconds. */
    static final int RETRY_DELAY_SECONDS = 5;

    private final TripleStore store;

    /**
     * Creates the initializer.
     *
     * @param store the SPARQL store
     */
    public TriplestoreInitializer(final TripleStore store) {
        this.store = store;
    }

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        Thread.ofVirtual().name("triplestore-initializer")
            .start(() -> initialize(MAX_ATTEMPTS, Duration.ofSeconds(RETRY_DELAY_SECONDS)));
    }

    /**
     * Tries to prepare the store until it succeeds or the attempts are exhausted.
     *
     * @param attempts maximum number of attempts
     * @param delay    delay between attempts
     * @return {@code true} when the store is prepared
     */
    boolean initialize(final int attempts, final Duration delay) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (store.prepare()) {
                    log.info("Created {}", store.target());
                }
                return true;
            } catch (RestClientException e) {
                log.info("{} not ready (attempt {}/{}): {}", store.target(), attempt, attempts, e.getMessage());
                if (!sleep(delay)) {
                    return false;
                }
            }
        }
        log.warn("Giving up preparing {}", store.target());
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
