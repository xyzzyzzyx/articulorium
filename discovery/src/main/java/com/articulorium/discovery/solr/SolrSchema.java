package com.articulorium.discovery.solr;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which fields the Solr collection has.
 *
 * <p>Solr's {@code _default} config set creates a field only when the first document carrying it
 * is indexed, and the {@code edismax} parser refuses a whole search when one of its query fields
 * ({@code qf}, {@code pf}) does not exist. Searches therefore only use the configured fields the
 * collection has: explicit fields and fields matching a dynamic field ({@code *_txt_en},
 * {@code *_str}). The answer is remembered for {@value #TTL_SECONDS} seconds, so fields appear in
 * searches shortly after their first document is indexed.</p>
 */
@Component
public class SolrSchema {

    private static final Logger LOG = LoggerFactory.getLogger(SolrSchema.class);

    /** How long the field list is remembered. */
    static final int TTL_SECONDS = 60;

    private final SolrSearchClient solr;

    private final Clock clock;

    private volatile Snapshot snapshot;

    /**
     * Creates the schema view.
     *
     * @param solr the Solr client
     */
    @Autowired
    public SolrSchema(final SolrSearchClient solr) {
        this(solr, Clock.systemUTC());
    }

    /**
     * Creates the schema view with a clock.
     *
     * @param solr  the Solr client
     * @param clock the clock of the cache
     */
    public SolrSchema(final SolrSearchClient solr, final Clock clock) {
        this.solr = solr;
        this.clock = clock;
    }

    /**
     * Tells whether the collection has a field.
     *
     * @param field the field name
     * @return {@code true} when it is an explicit field or matches a dynamic field, and when the
     *     schema cannot be read (the search then fails as it would have, with Solr's diagnostics in
     *     the log)
     */
    public boolean has(final String field) {
        final Snapshot current = current();
        if (current == null) {
            return true;
        }
        if (current.fields().contains(field)) {
            return true;
        }
        for (final String pattern : current.dynamicFields()) {
            if (pattern.startsWith("*") && field.endsWith(pattern.substring(1))
                    || pattern.endsWith("*") && field.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private Snapshot current() {
        final Snapshot cached = snapshot;
        final Instant now = clock.instant();
        if (cached != null && cached.until().isAfter(now)) {
            return cached;
        }
        try {
            final SolrSearchClient.Schema schema = solr.schema();
            final Snapshot fresh = new Snapshot(Set.copyOf(schema.fields()), List.copyOf(schema.dynamicFields()),
                now.plus(Duration.ofSeconds(TTL_SECONDS)));
            snapshot = fresh;
            return fresh;
        } catch (SolrUnavailableException e) {
            LOG.debug("The schema of the Solr collection cannot be read: {}", e.getMessage());
            return cached;
        }
    }

    private record Snapshot(Set<String> fields, List<String> dynamicFields, Instant until) {
    }

}
