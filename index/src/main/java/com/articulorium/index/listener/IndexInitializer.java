package com.articulorium.index.listener;

import java.time.Duration;
import java.util.List;

import com.articulorium.index.backend.DocumentIndex;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates the Solr collection or Elasticsearch index once the application is ready, if it does not
 * exist yet.
 *
 * <p>The backend may still be starting when this service is ready, so creation is retried in the
 * background (every {@value #RETRY_DELAY_SECONDS}s, at most {@value #MAX_ATTEMPTS} times) without
 * delaying start-up.</p>
 */
@Slf4j
@Component
public class IndexInitializer implements ApplicationListener<ApplicationReadyEvent> {

    /** Maximum number of attempts. */
    static final int MAX_ATTEMPTS = 60;

    /** Delay between attempts in seconds. */
    static final int RETRY_DELAY_SECONDS = 5;

    private final DocumentIndex index;

    /**
     * Creates the initializer.
     *
     * @param index the search index
     */
    public IndexInitializer(final DocumentIndex index) {
        this.index = index;
    }

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        Thread.ofVirtual().name("index-initializer").start(() -> initialize(MAX_ATTEMPTS, Duration.ofSeconds(RETRY_DELAY_SECONDS)));
    }

    /**
     * Tries to prepare the index until it succeeds or the attempts are exhausted.
     *
     * @param attempts maximum number of attempts
     * @param delay    delay between attempts
     * @return {@code true} when the index is available
     */
    boolean initialize(final int attempts, final Duration delay) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (index.ensureIndex()) {
                    log.info("Created {}", index.target());
                } else {
                    log.info("{} is available", index.target());
                }
                declareFields();
                return true;
            } catch (RestClientException e) {
                log.info("{} not ready (attempt {}/{}): {}", index.target(), attempt, attempts, e.getMessage());
                if (!sleep(delay)) {
                    return false;
                }
            }
        }
        log.warn("Giving up preparing {}", index.target());
        return false;
    }

    /**
     * Declares the configured fields. The backend refusing a declaration (an unknown field type,
     * for example) is a configuration problem that retrying does not solve: it is logged, and the
     * index is used as it is.
     */
    private void declareFields() {
        try {
            final List<String> added = index.ensureFields();
            if (!added.isEmpty()) {
                log.info("Declared fields {} in {}", added, index.target());
            }
        } catch (HttpClientErrorException e) {
            log.warn("The configured fields were refused by {}: {}", index.target(), e.getMessage());
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
