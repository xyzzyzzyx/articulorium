package com.articulorium.harvest.classify;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Premis;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.RecordPaths;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The review queue of classified records: lists them, and accepts, rejects or retries their
 * classification for a person. Reads and writes use the person's own credentials (the platform's
 * Web Access Control decides), except that a retried classification asks the agent with the
 * harvester's, as when the record was harvested.
 */
public class ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewService.class);

    /** Largest page of the queue. */
    public static final int MAX_LIMIT = 100;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,200}");

    private final PlatformClient platform;

    private final RecordPaths paths;

    private final ClassificationStore store;

    private final RecordPlacer placer;

    private final ClassifyingIngestor ingestor;

    private final HarvestProperties properties;

    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param platform   the platform, with the harvester's credentials (callers get their own)
     * @param paths      where records go
     * @param store      the classifications
     * @param placer     places accepted records
     * @param ingestor   classifies records
     * @param properties the harvest settings
     * @param clock      the clock
     */
    public ReviewService(final PlatformClient platform, final RecordPaths paths, final ClassificationStore store,
            final RecordPlacer placer, final ClassifyingIngestor ingestor, final HarvestProperties properties,
            final Clock clock) {
        this.platform = platform;
        this.paths = paths;
        this.store = store;
        this.placer = placer;
        this.ingestor = ingestor;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Lists classifications, the most recently changed first.
     *
     * @param authorization the caller's credentials
     * @param source        only this source's, {@code null} for every source
     * @param open          only those waiting for a person
     * @param offset        classifications skipped
     * @param limit         classifications wanted
     * @return the page
     */
    public Page list(final String authorization, final String source, final boolean open, final int offset,
            final int limit) {
        if (source != null && !properties.getSources().containsKey(source)) {
            throw new ReviewException(404, ReviewException.UNKNOWN_SOURCE, "No source " + source);
        }
        final PlatformClient caller = platform.as(authorization);
        final List<String> conditions = new ArrayList<>();
        conditions.add("rdf_type=" + (open ? Articulorium.REVIEW_PENDING : Articulorium.CLASSIFICATION));
        conditions.add("fedora_id=info:fedora/" + (source == null ? paths.root() : paths.source(source)) + "/*");
        final PlatformClient.SearchPage page = caller.search(conditions, Math.max(0, offset),
            Math.max(1, Math.min(limit, MAX_LIMIT)));
        final List<Classification> classifications = new ArrayList<>();
        for (final String path : page.paths()) {
            ResourcePaths.parentOf(path).flatMap(record -> store.read(caller, record)).map(this::flagged)
                .ifPresent(classifications::add);
        }
        return new Page(classifications, page.total(), offset);
    }

    /**
     * Returns a record's classification.
     *
     * @param authorization the caller's credentials
     * @param source        the source
     * @param record        the record's name
     * @return the classification
     */
    public Classification get(final String authorization, final String source, final String record) {
        return flagged(read(platform.as(authorization), source, record));
    }

    /**
     * Accepts a record's classification: places the record in a collection.
     *
     * @param authorization the caller's credentials
     * @param caller        who accepts (for the record of the decision)
     * @param source        the source
     * @param record        the record's name
     * @param collection    the collection, {@code null} for the suggested existing collection
     * @param topology      the topology id, {@code none} for a plain object, {@code null} for the
     *                      suggested existing topology
     * @return the accepted classification
     */
    public Classification accept(final String authorization, final String caller, final String source,
            final String record, final String collection, final String topology) {
        final PlatformClient platformAsCaller = platform.as(authorization);
        final Classification classification = read(platformAsCaller, source, record);
        if (classification.status() == Classification.Status.ACCEPTED) {
            throw new ReviewException(409, ReviewException.DECIDED, "The record was placed already at /"
                + classification.placedAt());
        }
        final Suggestion suggestion = classification.suggestion();
        String target = collection;
        if (target == null || target.isBlank()) {
            if (suggestion == null || !Suggestion.EXISTING_COLLECTION.equals(suggestion.placement())
                    || suggestion.collection() == null) {
                throw new ReviewException(409, ReviewException.TARGET_REQUIRED,
                    "The suggestion names no existing collection: choose one (create the proposed one first)");
            }
            target = suggestion.collection().path();
        } else if (!ResourcePaths.isSafe(target)) {
            throw new ReviewException(422, ReviewException.NOT_PLACED, "The collection is not a resource path");
        }
        String topologyId;
        if (topology == null || topology.isBlank()) {
            if (suggestion != null && suggestion.topology() != null
                    && Suggestion.Topology.NEW.equals(suggestion.topology().kind())) {
                throw new ReviewException(409, ReviewException.TOPOLOGY_REQUIRED,
                    "The suggestion proposes a new topology: create it first, or choose another");
            }
            topologyId = suggestion == null ? null : ClassifyingIngestor.topology(suggestion);
        } else {
            topologyId = "none".equals(topology) ? null : topology;
        }
        try {
            final Classification accepted = placer.place(platformAsCaller, authorization, classification, target,
                topologyId, caller);
            LOG.info("{} accepted the classification of {} of {}: /{}", caller, classification.identifier(), source,
                accepted.placedAt());
            return flagged(accepted);
        } catch (TopologyClient.PlacementException e) {
            throw new ReviewException(e.getStatus(), ReviewException.NOT_PLACED, e.getMessage());
        } catch (HarvestException e) {
            throw platformRefusal(e);
        }
    }

    /**
     * Rejects a record's classification: the record stays where it was harvested.
     *
     * @param authorization the caller's credentials
     * @param caller        who rejects
     * @param source        the source
     * @param record        the record's name
     * @return the rejected classification
     */
    public Classification reject(final String authorization, final String caller, final String source,
            final String record) {
        final PlatformClient platformAsCaller = platform.as(authorization);
        final Classification classification = read(platformAsCaller, source, record);
        if (classification.status() == Classification.Status.ACCEPTED
                || classification.status() == Classification.Status.PLACING) {
            throw new ReviewException(409, ReviewException.DECIDED, "The record is placed at /"
                + classification.placedAt() + "; move or delete its item instead");
        }
        final Classification rejected = new Classification(classification.source(), classification.record(),
            classification.identifier(), classification.title(), Classification.Status.REJECTED,
            classification.suggestion(), classification.failure(), false, classification.classifiedAt(), null, caller,
            clock.instant());
        try {
            store.write(platformAsCaller, rejected);
        } catch (HarvestException e) {
            throw platformRefusal(e);
        }
        LOG.info("{} rejected the classification of {} of {}", caller, classification.identifier(), source);
        return flagged(rejected);
    }

    /**
     * Classifies a record again (after a failure, or to get a new suggestion).
     *
     * @param authorization the caller's credentials
     * @param source        the source
     * @param record        the record's name
     * @return the new classification
     */
    public Classification retry(final String authorization, final String source, final String record) {
        final PlatformClient platformAsCaller = platform.as(authorization);
        final Classification classification = read(platformAsCaller, source, record);
        if (classification.status() == Classification.Status.ACCEPTED
                || classification.status() == Classification.Status.PLACING) {
            throw new ReviewException(409, ReviewException.DECIDED, "The record is placed at /"
                + classification.placedAt());
        }
        final String staging = classification.record();
        final Model model = platformAsCaller.rdf(staging).orElseThrow(() -> new ReviewException(404,
            ReviewException.NOT_FOUND, "No record /" + staging));
        ingestor.classify(source, recordOf(model, platformAsCaller.iri(staging), classification), staging);
        return flagged(read(platformAsCaller, source, record));
    }

    private Classification read(final PlatformClient caller, final String source, final String record) {
        if (!properties.getSources().containsKey(source)) {
            throw new ReviewException(404, ReviewException.UNKNOWN_SOURCE, "No source " + source);
        }
        if (record == null || !NAME.matcher(record).matches()) {
            throw new ReviewException(404, ReviewException.NOT_FOUND, "No record " + record);
        }
        final String staging = ResourcePaths.child(paths.source(source), record);
        try {
            return store.read(caller, staging).orElseThrow(() -> new ReviewException(404, ReviewException.NOT_FOUND,
                "The record /" + staging + " has no classification"));
        } catch (HarvestException e) {
            throw platformRefusal(e);
        }
    }

    private Classification flagged(final Classification classification) {
        final boolean uncertain = classification.suggestion() != null
            && classification.suggestion().confidenceOrZero() < properties.getClassification().getReviewThreshold();
        return new Classification(classification.source(), classification.record(), classification.identifier(),
            classification.title(), classification.status(), classification.suggestion(), classification.failure(),
            uncertain, classification.classifiedAt(), classification.placedAt(), classification.decidedBy(),
            classification.decidedAt());
    }

    /**
     * Rebuilds a harvested record from its copy, to classify it again.
     */
    private static HarvestedRecord recordOf(final Model model, final String iri, final Classification classification) {
        final Resource subject = model.createResource(iri);
        final List<String> sets = new ArrayList<>();
        subject.listProperties(model.createProperty(Articulorium.HARVEST_SET)).forEachRemaining(statement -> {
            if (statement.getObject().isLiteral()) {
                sets.add(statement.getObject().asLiteral().getLexicalForm());
            }
        });
        final List<HarvestedFile> files = new ArrayList<>();
        for (final Statement statement : subject.listProperties(model.createProperty(Articulorium.HARVEST_FILE)).toList()) {
            if (statement.getObject().isURIResource()) {
                final Resource file = statement.getObject().asResource();
                final String mediaType = Optional.ofNullable(file.getProperty(model.createProperty(Premis.HAS_MIME_TYPE)))
                    .map(Statement::getObject).filter(RDFNode::isLiteral).map(node -> node.asLiteral().getLexicalForm())
                    .orElse(null);
                files.add(new HarvestedFile(URI.create(file.getURI()), null, mediaType, null));
            }
        }
        final String identifier = classification.identifier() != null ? classification.identifier()
            : Optional.ofNullable(subject.getProperty(model.createProperty(Articulorium.HARVEST_IDENTIFIER)))
                .map(statement -> statement.getObject().toString()).orElse(iri);
        model.removeAll(subject, model.createProperty(Rdf.TYPE), model.createResource(Articulorium.HARVESTED_RECORD));
        return new HarvestedRecord(identifier, iri, null, false, sets, model, files);
    }

    private static ReviewException platformRefusal(final HarvestException e) {
        final int status = PlatformClient.statusOf(e);
        if (status == 401 || status == 403 || status == 404 || status == 409 || status == 412) {
            return new ReviewException(status, ReviewException.REFUSED, e.getMessage());
        }
        return new ReviewException(503, ReviewException.PLATFORM_UNAVAILABLE, e.getMessage());
    }

    /**
     * A page of the review queue.
     *
     * @param classifications the classifications
     * @param total           how many match
     * @param offset          how many were skipped
     */
    public record Page(List<Classification> classifications, long total, int offset) {
    }

}
