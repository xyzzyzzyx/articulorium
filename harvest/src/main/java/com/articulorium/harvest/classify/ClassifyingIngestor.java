package com.articulorium.harvest.classify;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Predicate;

import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.core.RecordIngestor;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.ingest.RecordPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The ingest of harvested records with the AI agent in the loop.
 *
 * <p>Records are ingested exactly as without the agent ({@link PlatformIngestor}); then each record
 * that has no classification yet is classified, and the suggestion is stored below the record for a
 * person to review. When the agent cannot classify a record (unavailable, failing, turned off) the
 * classification is stored as {@code FAILED} with the reason, to retry from the review queue: the
 * harvest itself goes on, and its outcome does not depend on the agent.</p>
 *
 * <p>Records already placed in a collection are updated in their item (and deleted with it), so a
 * placed record is never ingested a second time where it was harvested. With
 * {@code auto-accept-threshold} set, a suggestion of an existing collection (with an existing
 * topology or none) at least that sure is accepted at once, with the harvester's credentials.</p>
 */
public class ClassifyingIngestor implements RecordIngestor {

    private static final Logger LOG = LoggerFactory.getLogger(ClassifyingIngestor.class);

    /** Who accepts automatically. */
    public static final String AUTO = "auto";

    private final PlatformIngestor delegate;

    private final PlatformClient platform;

    private final PlatformAuthorization authorization;

    private final RecordPaths paths;

    private final ClassificationStore store;

    private final AgentClient agent;

    private final RecordPlacer placer;

    private final HarvestProperties.Classification settings;

    private final Predicate<String> classified;

    private final Clock clock;

    private final MeterRegistry meters;

    /**
     * Creates the ingestor.
     *
     * @param delegate      ingest without the agent
     * @param platform      the platform, with the harvester's credentials
     * @param authorization the harvester's credentials
     * @param paths         where records go
     * @param store         the classifications
     * @param agent         the AI agent
     * @param placer        places accepted records
     * @param settings      the classification settings
     * @param classified    tells which sources are classified
     * @param clock         the clock
     * @param meters        the meter registry
     */
    public ClassifyingIngestor(final PlatformIngestor delegate, final PlatformClient platform,
            final PlatformAuthorization authorization, final RecordPaths paths, final ClassificationStore store,
            final AgentClient agent, final RecordPlacer placer, final HarvestProperties.Classification settings,
            final Predicate<String> classified, final Clock clock, final MeterRegistry meters) {
        this.delegate = delegate;
        this.platform = platform;
        this.authorization = authorization;
        this.paths = paths;
        this.store = store;
        this.agent = agent;
        this.placer = placer;
        this.settings = settings;
        this.classified = classified;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void begin(final String sourceId) {
        delegate.begin(sourceId);
    }

    @Override
    public IngestAction ingest(final String sourceId, final HarvestedRecord record) {
        if (!classified.test(sourceId)) {
            return delegate.ingest(sourceId, record);
        }
        final String staging = paths.record(sourceId, record.identifier());
        final Optional<Classification> existing = store.read(platform, staging);
        final String item = existing.map(Classification::placedAt).orElse(null);
        if (item != null) {
            if (record.deleted()) {
                platform.delete(item);
                platform.delete(staging);
                return IngestAction.DELETED;
            }
            placer.writeRecord(platform, item, RecordPlacer.parse(delegate.body(sourceId, record, platform.iri(item))));
            return IngestAction.UPDATED;
        }
        final IngestAction action = delegate.ingest(sourceId, record);
        if (!record.deleted() && existing.isEmpty()) {
            classify(sourceId, record, staging);
        }
        return action;
    }

    /**
     * Classifies a record and stores the suggestion (or why there is none). Never fails the record.
     *
     * @param sourceId the source
     * @param record   the record
     * @param staging  the path of its copy
     */
    public void classify(final String sourceId, final HarvestedRecord record, final String staging) {
        final String title = AgentClient.title(record);
        Classification classification;
        try {
            final Suggestion suggestion = agent.classify(paths.source(sourceId), sourceId, record);
            classification = new Classification(sourceId, staging, record.identifier(), title,
                Classification.Status.PENDING, suggestion, null, false, clock.instant(), null, null, null);
        } catch (AgentClient.AgentUnavailableException e) {
            classification = new Classification(sourceId, staging, record.identifier(), title,
                Classification.Status.FAILED, null, e.getReason(), false, clock.instant(), null, null, null);
        }
        try {
            store.write(platform, classification);
        } catch (HarvestException e) {
            count(sourceId, "unrecorded");
            LOG.warn("The classification of {} of {} could not be stored: {}", record.identifier(), sourceId,
                e.getMessage());
            return;
        }
        if (classification.status() == Classification.Status.FAILED) {
            count(sourceId, "failed");
            return;
        }
        if (autoAccept(classification.suggestion())) {
            try {
                placer.place(platform, authorization.header(), classification,
                    classification.suggestion().collection().path(), topology(classification.suggestion()), AUTO);
                count(sourceId, "auto-accepted");
                return;
            } catch (TopologyClient.PlacementException | HarvestException e) {
                LOG.warn("Automatic placement of {} of {} failed, it waits for review: {}", record.identifier(),
                    sourceId, e.getMessage());
            }
        }
        count(sourceId, "pending");
    }

    /**
     * Tells whether a suggestion is accepted without a person.
     *
     * @param suggestion the suggestion
     * @return {@code true} for an existing collection, an existing topology or none, and a confidence
     *         from the threshold
     */
    boolean autoAccept(final Suggestion suggestion) {
        return settings.getAutoAcceptThreshold() != null && suggestion != null
            && Suggestion.EXISTING_COLLECTION.equals(suggestion.placement()) && suggestion.collection() != null
            && suggestion.confidenceOrZero() >= settings.getAutoAcceptThreshold()
            && (suggestion.topology() == null || !Suggestion.Topology.NEW.equals(suggestion.topology().kind()));
    }

    /**
     * Returns the existing topology a suggestion names.
     *
     * @param suggestion the suggestion
     * @return the topology's id, or {@code null} for none
     */
    static String topology(final Suggestion suggestion) {
        return suggestion.topology() != null && Suggestion.Topology.EXISTING.equals(suggestion.topology().kind())
            ? suggestion.topology().id() : null;
    }

    private void count(final String sourceId, final String outcome) {
        meters.counter("harvest.classifications", "source", sourceId, "outcome", outcome).increment();
    }

}
