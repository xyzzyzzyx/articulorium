package com.articulorium.harvest.classify;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import com.articulorium.domain.rdf.ResourcePaths;
import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Ldp;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestException;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.core.RecordIngestor;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.ingest.RecordPaths;
import com.articulorium.harvest.source.DublinCore;

import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The ingest of harvested records with the AI agent in the loop.
 *
 * <p>Records are ingested exactly as without the agent ({@link PlatformIngestor}); then each record
 * that has no classification yet (or one that failed or found no placement) is classified, and the suggestion is stored below the record for a
 * person to review. When the agent cannot classify a record (unavailable, failing, turned off) the
 * classification is stored as {@code FAILED} with the reason, to retry from the review queue: the
 * harvest itself goes on, and its outcome does not depend on the agent.</p>
 *
 * <p>Records already placed in a collection are updated in their item (and deleted with it), so a
 * placed record is never ingested a second time where it was harvested. With {@code auto-accept}
 * (the default, or the source's own choice: {@link SourcePolicy}) a suggestion is accepted at once,
 * with the harvester's credentials: the record goes to the suggested existing collection, or to the
 * proposed collection (and community), created in the community structure unless one of that name
 * exists; a proposed topology is drafted by the agent and saved ({@link TopologyProposer}) unless
 * one of that name exists. Suggestions of no placement, of structure the settings do not let
 * automatic acceptance create ({@code create-communities}, {@code create-collections},
 * {@code create-topologies}), those below {@code auto-accept-threshold}, and records whose
 * placement fails wait for review.</p>
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

    private final TopologyProposer proposer;

    private final HarvestProperties.Classification settings;

    private final Function<String, SourcePolicy> policies;

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
     * @param proposer      creates proposed topologies
     * @param settings      the classification settings
     * @param policies      what classification does with the records of each source
     * @param clock         the clock
     * @param meters        the meter registry
     */
    public ClassifyingIngestor(final PlatformIngestor delegate, final PlatformClient platform,
            final PlatformAuthorization authorization, final RecordPaths paths, final ClassificationStore store,
            final AgentClient agent, final RecordPlacer placer, final TopologyProposer proposer,
            final HarvestProperties.Classification settings, final Function<String, SourcePolicy> policies,
            final Clock clock, final MeterRegistry meters) {
        this.delegate = delegate;
        this.platform = platform;
        this.authorization = authorization;
        this.paths = paths;
        this.store = store;
        this.agent = agent;
        this.placer = placer;
        this.proposer = proposer;
        this.settings = settings;
        this.policies = policies;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void begin(final String sourceId) {
        delegate.begin(sourceId);
    }

    @Override
    public IngestAction ingest(final String sourceId, final HarvestedRecord record) {
        if (!policies.apply(sourceId).classify()) {
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
        if (!record.deleted() && (existing.isEmpty() || unclassified(existing.get()))) {
            classify(sourceId, record, staging);
        }
        return action;
    }

    /**
     * Tells whether a record harvested again is classified again: its classification failed, or the
     * agent found no placement and nobody decided since. A full harvest so retries what the agent
     * could not do the first time; suggestions waiting for review and rejected ones are left alone.
     */
    static boolean unclassified(final Classification classification) {
        if (classification.status() == Classification.Status.FAILED) {
            return true;
        }
        return classification.status() == Classification.Status.PENDING && (classification.suggestion() == null
            || classification.suggestion().placement() == null
            || Suggestion.NONE.equals(classification.suggestion().placement()));
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
        if (autoAccept(policies.apply(sourceId), classification.suggestion())) {
            try {
                final Classification accepted = withTopology(sourceId, record, classification);
                final String topology = proposer.itemTopology(topology(accepted.suggestion()), authorization.header());
                placer.place(platform, authorization.header(), accepted, target(accepted.suggestion()), topology, AUTO);
                count(sourceId, "auto-accepted");
                return;
            } catch (TopologyClient.PlacementException | HarvestException | AgentClient.AgentUnavailableException e) {
                LOG.warn("Automatic placement of {} of {} failed, it waits for review: {}", record.identifier(),
                    sourceId, e.getMessage());
            }
        }
        count(sourceId, "pending");
    }

    /**
     * Replaces a proposed topology by one that exists, creating it when there is none, before
     * anything else is created for the record. The classification then names that topology, so a
     * record whose placement fails afterwards is reviewed with it.
     */
    private Classification withTopology(final String sourceId, final HarvestedRecord record,
            final Classification classification) {
        final Suggestion suggestion = classification.suggestion();
        if (suggestion.topology() == null || !Suggestion.Topology.NEW.equals(suggestion.topology().kind())) {
            return classification;
        }
        final Suggestion.Topology topology = proposer.resolve(sourceId, paths.source(sourceId), suggestion.topology(),
            record, authorization.header());
        final Classification resolved = new Classification(classification.source(), classification.record(),
            classification.identifier(), classification.title(), classification.status(),
            suggestion.withTopology(topology), classification.failure(), classification.uncertain(),
            classification.classifiedAt(), classification.placedAt(), classification.decidedBy(),
            classification.decidedAt());
        store.write(platform, resolved);
        return resolved;
    }

    /**
     * Tells whether a suggestion is accepted without a person.
     *
     * @param policy     the policy of the record's source
     * @param suggestion the suggestion
     * @return {@code true} with {@code auto-accept} for an existing collection, or (where the policy
     *         lets them be created) a new collection of an existing community, or a new community
     *         with its collection; with an existing topology, none, or (where the policy lets them
     *         be created) a named new one; and a confidence from the threshold (when one is set)
     */
    boolean autoAccept(final SourcePolicy policy, final Suggestion suggestion) {
        if (!policy.autoAccept() || suggestion == null || suggestion.placement() == null) {
            return false;
        }
        if (policy.autoAcceptThreshold() != null && suggestion.confidenceOrZero() < policy.autoAcceptThreshold()) {
            return false;
        }
        if (suggestion.topology() != null && Suggestion.Topology.NEW.equals(suggestion.topology().kind())
                && !(policy.createTopologies() && suggestion.topology().name() != null
                    && !suggestion.topology().name().isBlank())) {
            return false;
        }
        return switch (suggestion.placement()) {
            case Suggestion.EXISTING_COLLECTION -> suggestion.collection() != null;
            case Suggestion.NEW_COLLECTION -> policy.createCollections() && suggestion.community() != null
                && suggestion.newCollection() != null;
            case Suggestion.NEW_COMMUNITY -> policy.createCommunities() && policy.createCollections()
                && suggestion.newCommunity() != null && suggestion.newCollection() != null;
            default -> false;
        };
    }

    /**
     * Returns the collection an automatically accepted record goes to, creating the proposed
     * community and collection when none of that name exists (records proposing the same structure
     * share it, as when a person creates it from the review queue).
     *
     * @param suggestion an automatically accepted suggestion
     * @return the collection's path
     * @throws TopologyClient.PlacementException when the proposed parent is outside the community
     *                                           structure or not a community
     */
    String target(final Suggestion suggestion) {
        if (Suggestion.EXISTING_COLLECTION.equals(suggestion.placement())) {
            return suggestion.collection().path();
        }
        final String root = ResourcePaths.normalize(settings.getCommunities());
        String parent = root;
        if (suggestion.community() != null) {
            parent = ResourcePaths.normalize(suggestion.community().path());
            if (!ResourcePaths.isSafe(parent) || !ResourcePaths.isSelfOrDescendant(parent, root)) {
                throw new TopologyClient.PlacementException(422, "/" + parent + " is not in the community structure");
            }
            if (!parent.equals(root) && !hasType(parent, Articulorium.COMMUNITY)) {
                throw new TopologyClient.PlacementException(422, "/" + parent + " is not a community");
            }
        }
        if (Suggestion.NEW_COMMUNITY.equals(suggestion.placement())) {
            parent = ensure(parent, Articulorium.COMMUNITY, suggestion.newCommunity());
        }
        return ensure(parent, Articulorium.COLLECTION, suggestion.newCollection());
    }

    private String ensure(final String parent, final String type, final Suggestion.NewStructure proposed) {
        final String slug = slug(proposed);
        final String path = parent + "/" + slug;
        if (hasType(path, type)) {
            return path;
        }
        final String title = proposed.title() == null || proposed.title().isBlank() ? slug : proposed.title().trim();
        final StringBuilder turtle = new StringBuilder("<> <").append(Rdf.TYPE).append("> <").append(type).append("> .\n");
        if (Articulorium.COLLECTION.equals(type)) {
            turtle.append("<> <").append(Rdf.TYPE).append("> <").append(Pcdm.COLLECTION).append("> .\n");
        }
        turtle.append("<> <").append(DublinCore.DCTERMS).append("title> ").append(RecordPlacer.turtleLiteral(title))
            .append(" .\n");
        if (proposed.description() != null && !proposed.description().isBlank()) {
            turtle.append("<> <").append(DublinCore.DCTERMS).append("description> ")
                .append(RecordPlacer.turtleLiteral(proposed.description().trim())).append(" .\n");
        }
        // a name taken by something else: the platform picks another one
        final String created = platform.create(parent, slug, turtle.toString(), Ldp.BASIC_CONTAINER);
        LOG.info("Created {} /{} for automatically accepted harvested records",
            Articulorium.COMMUNITY.equals(type) ? "community" : "collection", created);
        return created;
    }

    private boolean hasType(final String path, final String type) {
        final Optional<Model> model = platform.rdf(path);
        return model.isPresent() && model.get().contains(model.get().createResource(platform.iri(path)),
            model.get().createProperty(Rdf.TYPE), model.get().createResource(type));
    }

    /**
     * Derives the resource name of a proposed community or collection as the user interface does:
     * the proposed slug, or else the title, in lower-case letters, digits and hyphens.
     *
     * @param proposed the proposal
     * @return the name, never empty
     */
    static String slug(final Suggestion.NewStructure proposed) {
        final String base = proposed.slug() != null && !proposed.slug().isBlank() ? proposed.slug()
            : proposed.title() == null ? "" : proposed.title();
        String slug = Normalizer.normalize(base, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "untitled" : slug;
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
