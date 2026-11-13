package com.articulorium.harvest.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.articulorium.domain.vocabulary.Articulorium;
import com.articulorium.domain.vocabulary.Pcdm;
import com.articulorium.harvest.config.HarvestProperties;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.core.IngestAction;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.ingest.RecordPaths;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests the classification of harvested records end to end against an in-memory platform: ingest
 * with the agent in the loop, the review queue's decisions and the placement of accepted records in
 * the community structure.
 */
class ClassificationTest {

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final String COLLECTION = "communities/science/theses";

    private static final RecordPaths PATHS = new RecordPaths("harvested");

    private static final String STAGING = PATHS.record("repo", "oai:repo:1");

    private static final String NAME = com.articulorium.domain.rdf.ResourcePaths.nameOf(STAGING);

    private static final Suggestion SUGGESTION = new Suggestion(Suggestion.EXISTING_COLLECTION,
        new Suggestion.Ref(COLLECTION, "Theses", "Science > Theses"), null, null, null,
        new Suggestion.Topology("EXISTING", "thesis", "Thesis", null), 0.95, "A thesis.", List.of(), "ollama:test");

    private final FakePlatform platform = new FakePlatform();

    private final HarvestProperties properties = new HarvestProperties();

    private final Deque<Object> answers = new ArrayDeque<>();

    /** What the agent answers when asked for a topology. */
    private final Deque<Object> proposals = new ArrayDeque<>();

    /** The materials the agent was asked a topology for. */
    private final List<String> described = new java.util.ArrayList<>();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final RecordPaths paths = PATHS;

    private PlatformClient harvester;

    private ClassifyingIngestor ingestor;

    private ReviewService reviews;

    private ClassificationStore store;

    /** The agent, answering from {@link #answers}. */
    private final AgentClient agent = new AgentClient(null, "http://ai.test/", null) {

        @Override
        public Suggestion classify(final String context, final String source, final HarvestedRecord record) {
            final Object next = answers.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (Suggestion) next;
        }

        @Override
        public TopologyProposal suggestTopology(final String context, final String description) {
            described.add(description);
            final Object next = proposals.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (TopologyProposal) next;
        }

    };

    @BeforeEach
    void setUp() {
        final Clock clock = FakePlatform.clock();
        properties.setPlatformUrl(FakePlatform.BASE);
        properties.getSources().put("repo", new HarvestProperties.Source());
        final HarvestProperties.Source profiles = new HarvestProperties.Source();
        profiles.setClassify(false);
        properties.getSources().put("profiles", profiles);
        // review every record unless a test turns automatic acceptance on (the default)
        properties.getClassification().setAutoAccept(false);
        harvester = platform.client("Bearer harvester");
        store = new ClassificationStore();
        final RecordPlacer placer = new RecordPlacer(store, platform.topologies(), clock);
        ingestor = new ClassifyingIngestor(new PlatformIngestor(harvester, paths, clock), harvester,
            com.articulorium.harvest.ingest.PlatformAuthorization.fixed("Bearer harvester"), paths, store, agent, placer,
            new TopologyProposer(agent, platform.topologies(), meters), properties.getClassification(),
            source -> SourcePolicy.of(properties, source), clock, meters);
        reviews = new ReviewService(harvester, paths, store, placer, ingestor, properties, clock);
        platform.put("harvested", "<> <" + DCTERMS + "title> \"Harvested\" .\n")
            .put("harvested/repo", "<> <" + DCTERMS + "title> \"repo\" .\n")
            .put(COLLECTION, "<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + Articulorium.COLLECTION + "> .\n")
            .topology("thesis", """
                {"id": "thesis", "name": "Thesis", "root": {"key": "thesis", "label": "Thesis", "pcdmType": "Object",
                 "children": [{"min": 0, "max": -1, "initial": 1, "node": {"key": "chapter", "label": "Chapter",
                 "pcdmType": "Object"}}]}}
                """);
    }

    private static HarvestedRecord record(final String title) {
        return record("oai:repo:1", title);
    }

    private static HarvestedRecord record(final String identifier, final String title) {
        final Model model = ModelFactory.createDefaultModel();
        final Resource subject = model.createResource(identifier);
        subject.addProperty(model.createProperty(DCTERMS + "title"), title);
        subject.addProperty(model.createProperty(DCTERMS + "subject"), "Quantum dots");
        return new HarvestedRecord(identifier, identifier, null, false, List.of("theses"), model,
            List.of(new HarvestedFile(java.net.URI.create("https://repo.test/1.pdf"), "1.pdf", "application/pdf", 10L)));
    }

    private boolean auto(final Suggestion suggestion) {
        return ingestor.autoAccept(SourcePolicy.of(properties, "repo"), suggestion);
    }

    private static JsonNode json(final String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    private String title(final String path) {
        final Model model = platform.get(path);
        return model.listObjectsOfProperty(model.createResource(FakePlatform.BASE + path),
            model.createProperty(DCTERMS + "title")).next().asLiteral().getLexicalForm();
    }

    @Test
    void ingestsAsBeforeAndStoresTheSuggestionForReview() {
        answers.add(SUGGESTION);

        final IngestAction action = ingestor.ingest("repo", record("Quantum dots in silicon"));

        assertThat(action).isEqualTo(IngestAction.CREATED);
        assertThat(title(STAGING)).isEqualTo("Quantum dots in silicon");
        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.PENDING);
        assertThat(classification.suggestion()).isEqualTo(SUGGESTION);
        assertThat(classification.title()).isEqualTo("Quantum dots in silicon");
        final Model stored = platform.get(STAGING + "/classification");
        assertThat(stored.contains(null, stored.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            stored.createResource(Articulorium.REVIEW_PENDING))).isTrue();
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "pending").count()).isEqualTo(1);
    }

    @Test
    void classifiesARecordOnlyOnce() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots in silicon"));

        assertThat(ingestor.ingest("repo", record("Quantum dots in silicon, revised"))).isEqualTo(IngestAction.UPDATED);

        assertThat(answers).isEmpty();
        assertThat(title(STAGING)).isEqualTo("Quantum dots in silicon, revised");
    }

    @Test
    void recordsAFailedClassificationWithoutFailingTheRecord() {
        answers.add(new AgentClient.AgentUnavailableException("MODEL_UNAVAILABLE", "no model"));

        assertThat(ingestor.ingest("repo", record("Quantum dots"))).isEqualTo(IngestAction.CREATED);

        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.FAILED);
        assertThat(classification.failure()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(classification.status().open()).isTrue();
    }

    @Test
    void ingestsEvenWhenTheClassificationCannotBeStored() {
        answers.add(SUGGESTION);
        final ClassifyingIngestor failing = new ClassifyingIngestor(new PlatformIngestor(harvester, paths,
            FakePlatform.clock()), harvester, null, paths, new ClassificationStore() {
                @Override
                public void write(final PlatformClient client, final Classification classification) {
                    throw com.articulorium.harvest.core.HarvestException.recoverable("platform down", null);
                }
            }, agent, null, null, properties.getClassification(), source -> SourcePolicy.of(properties, source),
            FakePlatform.clock(), meters);

        assertThat(failing.ingest("repo", record("Quantum dots"))).isEqualTo(IngestAction.CREATED);
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "unrecorded").count())
            .isEqualTo(1);
    }

    @Test
    void leavesSourcesThatAreNotClassifiedAlone() {
        ingestor.ingest("profiles", record("Jane Doe"));

        assertThat(platform.exists(PATHS.record("profiles", "oai:repo:1") + "/classification")).isFalse();
        assertThat(platform.requests()).noneMatch(request -> request.contains("classification"));
    }

    @Test
    void placesAnAcceptedRecordInItsCollectionWithItsTopology() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots in silicon"));

        final Classification accepted = reviews.accept("Bearer alice", "info:oidc/agent/alice", "repo",
            NAME, null, null);

        final String item = COLLECTION + "/" + NAME;
        assertThat(accepted.status()).isEqualTo(Classification.Status.ACCEPTED);
        assertThat(accepted.placedAt()).isEqualTo(item);
        assertThat(accepted.decidedBy()).isEqualTo("info:oidc/agent/alice");
        final Model model = platform.get(item);
        final Resource subject = model.createResource(FakePlatform.BASE + item);
        // the topology's structure and the record's metadata
        assertThat(subject.hasProperty(model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            model.createResource(Pcdm.OBJECT))).isTrue();
        assertThat(subject.hasProperty(model.createProperty(Articulorium.TOPOLOGY), "thesis")).isTrue();
        assertThat(subject.hasProperty(model.createProperty(DCTERMS + "subject"), "Quantum dots")).isTrue();
        assertThat(subject.hasProperty(model.createProperty(Articulorium.HARVEST_IDENTIFIER), "oai:repo:1")).isTrue();
        assertThat(subject.hasProperty(model.createProperty(Articulorium.HARVEST_SOURCE), "repo")).isTrue();
        assertThat(model.listStatements(null, model.createProperty("http://fedora.info/definitions/v4/repository#created"),
            (org.apache.jena.rdf.model.RDFNode) null).toList()).isEmpty();
        assertThat(platform.exists(item + "/files")).isTrue();
        // what was harvested is a pointer now: the record is held once
        final Model stub = platform.get(STAGING);
        assertThat(stub.contains(stub.createResource(FakePlatform.BASE + STAGING),
            stub.createProperty(Articulorium.PLACED_AT), stub.createResource(FakePlatform.BASE + item))).isTrue();
        assertThat(stub.listObjectsOfProperty(stub.createProperty(DCTERMS + "subject")).toList()).isEmpty();
        // with the caller's credentials
        assertThat(platform.requests()).filteredOn(request -> request.startsWith("POST") || request.contains(item))
            .allMatch(request -> request.endsWith("Bearer alice"));
    }

    @Test
    void updatesAndDeletesPlacedRecordsInTheirItems() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));
        reviews.accept("Bearer alice", "alice", "repo", NAME, null, null);
        final String item = COLLECTION + "/" + NAME;

        assertThat(ingestor.ingest("repo", record("Quantum dots, second edition"))).isEqualTo(IngestAction.UPDATED);

        assertThat(title(item)).isEqualTo("Quantum dots, second edition");
        assertThat(platform.get(item).contains(null, platform.get(item).createProperty(Articulorium.TOPOLOGY),
            "thesis")).isTrue();
        assertThat(platform.get(STAGING).listObjectsOfProperty(platform.get(STAGING).createProperty(DCTERMS + "title"))
            .toList()).isEmpty();
        assertThat(answers).isEmpty();

        assertThat(ingestor.ingest("repo", HarvestedRecord.deleted("oai:repo:1", "oai:repo:1", null)))
            .isEqualTo(IngestAction.DELETED);
        assertThat(platform.exists(item)).isFalse();
        assertThat(platform.exists(STAGING)).isFalse();
    }

    @Test
    void acceptsIntoAnotherCollectionAsAPlainObject() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));
        platform.put("communities/science/papers",
            "<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + Articulorium.COLLECTION + "> .\n");

        final Classification accepted = reviews.accept("Bearer alice", "alice", "repo", NAME,
            "communities/science/papers", "none");

        assertThat(accepted.placedAt()).isEqualTo("communities/science/papers/" + NAME);
        final Model model = platform.get(accepted.placedAt());
        assertThat(model.contains(null, model.createProperty(Articulorium.TOPOLOGY))).isFalse();
        assertThat(model.contains(model.createResource(FakePlatform.BASE + accepted.placedAt()),
            model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), model.createResource(Pcdm.OBJECT)))
            .isTrue();
    }

    @Test
    void resumesAnInterruptedPlacementOnTheSameItem() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));
        // the item is built and the classification says so, then the platform fails
        platform.failPuts(0);
        final Classification pending = store.read(harvester, STAGING).orElseThrow();
        final RecordPlacer failing = new RecordPlacer(store, platform.topologies(), FakePlatform.clock()) {
            @Override
            public void writeRecord(final PlatformClient client, final String item, final Model record) {
                throw new TopologyClient.PlacementException(503, "platform down");
            }
        };
        assertThatThrownBy(() -> failing.place(platform.client("Bearer alice"), "Bearer alice", pending, COLLECTION,
            "thesis", "alice")).isInstanceOf(TopologyClient.PlacementException.class);
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PLACING);

        final Classification accepted = reviews.accept("Bearer alice", "alice", "repo", NAME, null,
            null);

        assertThat(accepted.placedAt()).isEqualTo(COLLECTION + "/" + NAME);
        assertThat(platform.exists(COLLECTION + "/" + NAME + "-2")).isFalse();
    }

    @Test
    void needsATargetWhenTheSuggestionProposesNewStructures() {
        answers.add(new Suggestion("NEW_COLLECTION", null, new Suggestion.Ref("communities/science", "Science", "Science"),
            null, new Suggestion.NewStructure("Theses", null, "theses"),
            new Suggestion.Topology("NEW", null, "Thesis with data", null), 0.4, "No theses yet.", List.of(), null));
        ingestor.ingest("repo", record("Quantum dots"));

        assertThatThrownBy(() -> reviews.accept("Bearer alice", "alice", "repo", NAME, null, null))
            .isInstanceOfSatisfying(ReviewException.class,
                e -> assertThat(e.getReason()).isEqualTo(ReviewException.TARGET_REQUIRED));
        assertThatThrownBy(() -> reviews.accept("Bearer alice", "alice", "repo", NAME, COLLECTION,
            null)).isInstanceOfSatisfying(ReviewException.class,
                e -> assertThat(e.getReason()).isEqualTo(ReviewException.TOPOLOGY_REQUIRED));
        // once created (by a person, in the user interface), the new collection is chosen explicitly
        assertThat(reviews.accept("Bearer alice", "alice", "repo", NAME, COLLECTION, "thesis")
            .status()).isEqualTo(Classification.Status.ACCEPTED);
        assertThat(reviews.get("Bearer alice", "repo", NAME).uncertain()).isTrue();
    }

    @Test
    void refusesToPlaceRecordsOutsideCollections() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));

        assertThatThrownBy(() -> reviews.accept("Bearer alice", "alice", "repo", NAME,
            "harvested", null)).isInstanceOfSatisfying(ReviewException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(422);
                assertThat(e.getReason()).isEqualTo(ReviewException.NOT_PLACED);
            });
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PENDING);
    }

    @Test
    void refusesCollectionPathsThatAddressAnotherResource() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));

        for (final String unsafe : new String[] {COLLECTION + "/../../harvested", COLLECTION + "?x=1", "a b"}) {
            assertThatThrownBy(() -> reviews.accept("Bearer alice", "alice", "repo", NAME, unsafe, null))
                .isInstanceOfSatisfying(ReviewException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getReason()).isEqualTo(ReviewException.NOT_PLACED);
                });
        }
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PENDING);
    }

    @Test
    void rejectsAndRetries() {
        answers.add(new AgentClient.AgentUnavailableException("AGENT_UNAVAILABLE", "down"));
        ingestor.ingest("repo", record("Quantum dots"));
        answers.add(SUGGESTION);

        final Classification retried = reviews.retry("Bearer alice", "repo", NAME);

        assertThat(retried.status()).isEqualTo(Classification.Status.PENDING);
        assertThat(retried.suggestion().collection().path()).isEqualTo(COLLECTION);

        final Classification rejected = reviews.reject("Bearer alice", "alice", "repo", NAME);
        assertThat(rejected.status()).isEqualTo(Classification.Status.REJECTED);
        assertThat(rejected.decidedBy()).isEqualTo("alice");
        assertThat(store.read(harvester, STAGING).map(Classification::status)).contains(Classification.Status.REJECTED);
        // a rejected record stays where it was harvested, and is not classified again
        ingestor.ingest("repo", record("Quantum dots"));
        assertThat(store.read(harvester, STAGING).map(Classification::status)).contains(Classification.Status.REJECTED);
    }

    @Test
    void refusesUnknownSourcesAndRecords() {
        assertThatThrownBy(() -> reviews.get("Bearer alice", "nope", "x")).isInstanceOfSatisfying(ReviewException.class,
            e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> reviews.get("Bearer alice", "repo", "../etc")).isInstanceOfSatisfying(
            ReviewException.class, e -> assertThat(e.getReason()).isEqualTo(ReviewException.NOT_FOUND));
        assertThatThrownBy(() -> reviews.get("Bearer alice", "repo", "missing")).isInstanceOfSatisfying(
            ReviewException.class, e -> assertThat(e.getReason()).isEqualTo(ReviewException.NOT_FOUND));
    }

    @Test
    void acceptsConfidentSuggestionsAutomaticallyWhenConfigured() {
        properties.getClassification().setAutoAccept(true);
        properties.getClassification().setAutoAcceptThreshold(0.9);
        answers.add(SUGGESTION);

        ingestor.ingest("repo", record("Quantum dots"));

        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
        assertThat(classification.decidedBy()).isEqualTo(ClassifyingIngestor.AUTO);
        assertThat(platform.exists(COLLECTION + "/" + NAME)).isTrue();
    }

    @Test
    void acceptsAutomaticallyByDefault() {
        assertThat(new HarvestProperties.Classification().getTimeout())
            .as("a model on a CPU takes minutes per call").isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(new HarvestProperties.Classification().isAutoAccept()).isTrue();
        assertThat(new HarvestProperties.Classification().getAutoAcceptThreshold()).isNull();
        assertThat(new HarvestProperties.Classification().getCommunities()).isEqualTo("communities");
    }

    @Test
    void neverAcceptsUncertainSuggestionsOrNoPlacementAutomatically() {
        properties.getClassification().setAutoAccept(true);
        properties.getClassification().setAutoAcceptThreshold(0.9);
        final Suggestion.NewStructure proposed = new Suggestion.NewStructure("Data", null, null);
        final Suggestion.Ref science = new Suggestion.Ref("communities/science", "Science", "Science");

        assertThat(auto(SUGGESTION)).isTrue();
        assertThat(auto(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, null, 0.89, null, null, null))).isFalse();
        assertThat(auto(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, new Suggestion.Topology("NEW", null, "x", null), 0.99, null, null, null))).isTrue();
        assertThat(auto(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, new Suggestion.Topology("NEW", null, " ", null), 0.99, null, null, null)))
            .as("a new topology needs a name").isFalse();
        assertThat(auto(new Suggestion(Suggestion.NEW_COLLECTION, null, science, null, proposed, null,
            0.99, null, null, null))).isTrue();
        assertThat(auto(new Suggestion(Suggestion.NEW_COLLECTION, null, null, null, proposed, null,
            0.99, null, null, null))).as("a new collection needs its community").isFalse();
        assertThat(auto(new Suggestion(Suggestion.NEW_COMMUNITY, null, null, proposed, proposed, null,
            0.99, null, null, null))).isTrue();
        assertThat(auto(new Suggestion(Suggestion.NONE, null, null, null, null, null, 0.99, null, null,
            null))).isFalse();
        assertThat(auto(null)).isFalse();

        // without a threshold any confidence will do; turned off, nothing is accepted automatically
        properties.getClassification().setAutoAcceptThreshold(null);
        assertThat(auto(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, null, 0.1, null, null, null))).isTrue();
        properties.getClassification().setAutoAccept(false);
        assertThat(auto(SUGGESTION)).isFalse();
    }

    @Test
    void createsOnlyTheStructureTheSettingsAllow() {
        properties.getClassification().setAutoAccept(true);
        final Suggestion.NewStructure proposed = new Suggestion.NewStructure("Data", null, null);
        final Suggestion.Ref science = new Suggestion.Ref("communities/science", "Science", "Science");
        final Suggestion newTopology = new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, new Suggestion.Topology("NEW", null, "Preprint", null), 0.99, null, null, null);
        final Suggestion newCollection = new Suggestion(Suggestion.NEW_COLLECTION, null, science, null, proposed, null,
            0.99, null, null, null);
        final Suggestion newCommunity = new Suggestion(Suggestion.NEW_COMMUNITY, null, null, proposed, proposed, null,
            0.99, null, null, null);
        assertThat(new HarvestProperties.Classification()).satisfies(defaults -> {
            assertThat(defaults.isCreateCommunities()).isTrue();
            assertThat(defaults.isCreateCollections()).isTrue();
            assertThat(defaults.isCreateTopologies()).isTrue();
        });

        properties.getClassification().setCreateTopologies(false);
        assertThat(auto(newTopology)).isFalse();
        assertThat(auto(SUGGESTION)).as("existing topologies are still used").isTrue();
        assertThat(auto(newCollection)).isTrue();

        properties.getClassification().setCreateCommunities(false);
        assertThat(auto(newCommunity)).isFalse();
        assertThat(auto(newCollection)).isTrue();

        properties.getClassification().setCreateCommunities(true);
        properties.getClassification().setCreateCollections(false);
        assertThat(auto(newCollection)).isFalse();
        assertThat(auto(newCommunity)).as("a new community comes with a new collection").isFalse();
        assertThat(auto(SUGGESTION)).isTrue();
    }

    @Test
    void aSourceDecidesAboutAutomaticAcceptanceForItself() {
        final HarvestProperties.Source arxiv = new HarvestProperties.Source();
        properties.getSources().put("arxiv", arxiv);

        // unset: the source follows the classification settings (reviewed, in this test)
        assertThat(SourcePolicy.of(properties, "arxiv").autoAccept()).isFalse();
        arxiv.setAutoAccept(true);
        assertThat(SourcePolicy.of(properties, "arxiv").autoAccept()).isTrue();
        assertThat(SourcePolicy.of(properties, "repo").autoAccept()).as("other sources are not affected").isFalse();
        assertThat(SourcePolicy.of(properties, "arxiv").describe())
            .isEqualTo("accepted automatically (creating communities, collections, topologies)");
        properties.getClassification().setAutoAccept(true);
        arxiv.setAutoAccept(false);
        assertThat(SourcePolicy.of(properties, "arxiv").autoAccept()).isFalse();
        assertThat(SourcePolicy.of(properties, "arxiv").describe()).isEqualTo("reviewed by a person");
        assertThat(SourcePolicy.of(properties, "repo").autoAccept()).isTrue();
        assertThat(SourcePolicy.of(properties, "profiles").describe()).isEqualTo("not classified");

        // the source's choice decides what happens to its records
        properties.getClassification().setAutoAccept(false);
        arxiv.setAutoAccept(true);
        platform.put("harvested/arxiv", "<> <" + DCTERMS + "title> \"arxiv\" .\n");
        answers.add(SUGGESTION);
        answers.add(SUGGESTION);
        ingestor.ingest("arxiv", record("oai:arXiv.org:2611.00001", "Quantum dots"));
        ingestor.ingest("repo", record("Quantum dots"));
        assertThat(store.read(harvester, PATHS.record("arxiv", "oai:arXiv.org:2611.00001")).orElseThrow().status())
            .isEqualTo(Classification.Status.ACCEPTED);
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PENDING);
    }

    private static final String PREPRINT_DRAFT = """
        {"id": "e-print", "name": "E-print", "description": "Preprints of articles", "builtIn": false,
         "root": {"key": "preprint", "label": "Preprint", "pcdmType": "Object",
          "files": [{"use": "http://pcdm.org/use#OriginalFile", "label": "Article", "mediaTypes": ["application/pdf"]}]}}
        """;

    private static Suggestion preprint(final Suggestion.Topology topology) {
        return new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null, null, null, topology,
            0.8, "A preprint.", List.of(), "ollama:test");
    }

    @Test
    void createsTheProposedTopologyOnceAndBuildsTheItemsFromIt() {
        properties.getClassification().setAutoAccept(true);
        final Suggestion proposing = preprint(new Suggestion.Topology("NEW", null, "Preprint",
            "Articles before peer review"));
        answers.add(proposing);
        answers.add(proposing);
        proposals.add(new TopologyProposal(TopologyProposal.NEW, null, json(PREPRINT_DRAFT), List.of(), "None fits.", 0.8));

        ingestor.ingest("repo", record("oai:repo:1", "Quantum dots"));
        ingestor.ingest("repo", record("oai:repo:2", "Quantum wells"));

        // saved under the proposed name, with the draft's structure
        final JsonNode saved = platform.topology("preprint");
        assertThat(saved).isNotNull();
        assertThat(saved.path("name").asString()).isEqualTo("Preprint");
        assertThat(saved.path("builtIn").asBoolean()).isFalse();
        assertThat(saved.path("root").path("files").get(0).path("mediaTypes").get(0).asString())
            .isEqualTo("application/pdf");
        assertThat(platform.topology("e-print")).isNull();
        // drafted once, from the proposal and the record that prompted it; saved with the harvester's credentials
        assertThat(described).hasSize(1);
        assertThat(described.get(0)).startsWith("Preprint: Articles before peer review")
            .contains("root of the topology is an Object").contains("application/pdf").contains("theses");
        assertThat(platform.requests()).contains("POST " + FakePlatform.TOPOLOGY + "topologies Bearer harvester");
        for (final String identifier : List.of("oai:repo:1", "oai:repo:2")) {
            final Classification classification = store.read(harvester, PATHS.record("repo", identifier)).orElseThrow();
            assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
            assertThat(classification.decidedBy()).isEqualTo(ClassifyingIngestor.AUTO);
            // the classification names the topology that exists now
            assertThat(classification.suggestion().topology())
                .isEqualTo(new Suggestion.Topology("EXISTING", "preprint", "Preprint", null));
            final Model item = platform.get(classification.placedAt());
            assertThat(item.createResource(FakePlatform.BASE + classification.placedAt())
                .hasProperty(item.createProperty(Articulorium.TOPOLOGY), "preprint")).isTrue();
        }
        assertThat(meters.counter("harvest.topologies", "source", "repo", "outcome", "created").count()).isEqualTo(1);
        assertThat(meters.counter("harvest.topologies", "source", "repo", "outcome", "reused").count()).isEqualTo(1);
    }

    @Test
    void usesAnExistingTopologyWhenTheAgentFindsThatOneFitsAfterAll() {
        properties.getClassification().setAutoAccept(true);
        answers.add(preprint(new Suggestion.Topology("NEW", null, "Dissertation", null)));
        proposals.add(new TopologyProposal(TopologyProposal.EXISTING, new TopologyProposal.Existing("thesis", "Thesis"),
            null, null, "A thesis.", 0.9));

        ingestor.ingest("repo", record("Quantum dots"));

        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
        assertThat(classification.suggestion().topology().id()).isEqualTo("thesis");
        assertThat(platform.topology("dissertation")).isNull();
    }

    @Test
    void findsATopologyOfTheProposedNameWhateverItsId() {
        properties.getClassification().setAutoAccept(true);
        answers.add(preprint(new Suggestion.Topology("NEW", null, " thesis ", null)));

        ingestor.ingest("repo", record("Quantum dots"));

        assertThat(store.read(harvester, STAGING).orElseThrow().suggestion().topology().id()).isEqualTo("thesis");
        assertThat(described).as("the agent is not asked for a topology that exists").isEmpty();
    }

    @Test
    void placesRecordsAsPlainObjectsWhenNoValidTopologyCanBeCreated() {
        properties.getClassification().setAutoAccept(true);
        platform.put("communities/science",
            "<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + Articulorium.COMMUNITY + "> .\n");
        final Suggestion.Topology proposed = new Suggestion.Topology("NEW", null, "Preprint", null);
        final Suggestion suggestion = new Suggestion(Suggestion.NEW_COLLECTION, null,
            new Suggestion.Ref("communities/science", "Science", "Science"), null,
            new Suggestion.NewStructure("Preprints", null, null), proposed, 0.8, null, List.of(), null);
        final List<Object> failures = List.of(
            // the topology API would refuse the draft
            new TopologyProposal(TopologyProposal.NEW, null, json(PREPRINT_DRAFT), List.of("The key preprint is used twice"),
                null, 0.5),
            // a collection of things is not what one record becomes
            new TopologyProposal(TopologyProposal.NEW, null, json(PREPRINT_DRAFT.replace("\"Object\"", "\"Collection\"")),
                List.of(), null, 0.5),
            // no draft at all
            new TopologyProposal(TopologyProposal.NEW, null, null, List.of(), null, 0.5),
            // a topology the agent made up
            new TopologyProposal(TopologyProposal.EXISTING, new TopologyProposal.Existing("made-up", "Made up"), null,
                List.of(), null, 0.5),
            new AgentClient.AgentUnavailableException("BUSY", "The agent is busy"));
        int n = 0;
        for (final Object failure : failures) {
            final String identifier = "oai:repo:" + ++n;
            answers.add(suggestion);
            proposals.add(failure);

            assertThat(ingestor.ingest("repo", record(identifier, "Quantum dots"))).isEqualTo(IngestAction.CREATED);

            final Classification classification = store.read(harvester, PATHS.record("repo", identifier)).orElseThrow();
            // the topology is a refinement: without it the record still reaches its collection
            assertThat(classification.status()).as("after %s", failure).isEqualTo(Classification.Status.ACCEPTED);
            assertThat(classification.suggestion().topology()).as("the proposal is kept").isEqualTo(proposed);
            assertThat(classification.placedAt()).startsWith("communities/science/preprints/");
            final Model item = platform.get(classification.placedAt());
            final Resource subject = item.createResource(FakePlatform.BASE + classification.placedAt());
            assertThat(subject.hasProperty(item.createProperty(Articulorium.TOPOLOGY))).isFalse();
            assertThat(subject.hasProperty(item.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                item.createResource(Pcdm.OBJECT))).isTrue();
        }
        assertThat(platform.topology("preprint")).isNull();
        assertThat(platform.exists("communities/science/preprints-2")).as("one collection for all of them").isFalse();
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "auto-accepted").count())
            .isEqualTo(failures.size());
    }

    @Test
    void placesARecordAsAPlainObjectWhenTheSuggestedTopologyIsACollection() {
        properties.getClassification().setAutoAccept(true);
        platform.topology("journal", """
            {"id": "journal", "name": "Journal", "root": {"key": "journal", "label": "Journal", "pcdmType": "Collection"}}
            """);
        answers.add(preprint(new Suggestion.Topology("EXISTING", "journal", "Journal", null)));

        ingestor.ingest("repo", record("Quantum dots"));

        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
        final Model item = platform.get(classification.placedAt());
        final Resource subject = item.createResource(FakePlatform.BASE + classification.placedAt());
        assertThat(subject.hasProperty(item.createProperty(Articulorium.TOPOLOGY))).isFalse();
        assertThat(subject.hasProperty(item.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            item.createResource(Pcdm.OBJECT))).isTrue();
    }

    @Test
    void classifiesAgainWhatTheAgentCouldNotPlaceWhenTheRecordIsHarvestedAgain() {
        properties.getClassification().setAutoAccept(true);
        final Suggestion none = new Suggestion(Suggestion.NONE, null, null, null, null,
            new Suggestion.Topology("NONE", null, null, null), 0.0, null, List.of(), null);
        // a failure, then no placement, then a suggestion: each harvest of the record tries again
        answers.add(new AgentClient.AgentUnavailableException("INVALID_OUTPUT", "unreadable"));
        ingestor.ingest("repo", record("Quantum dots"));
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.FAILED);

        answers.add(none);
        ingestor.ingest("repo", record("Quantum dots"));
        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PENDING);

        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));
        final Classification placed = store.read(harvester, STAGING).orElseThrow();
        assertThat(placed.status()).isEqualTo(Classification.Status.ACCEPTED);
        assertThat(platform.exists(COLLECTION + "/" + NAME)).isTrue();
        assertThat(answers).isEmpty();
    }

    @Test
    void leavesSuggestionsWaitingForAPersonAloneWhenTheRecordIsHarvestedAgain() {
        answers.add(SUGGESTION);
        ingestor.ingest("repo", record("Quantum dots"));
        reviews.reject("Bearer alice", "alice", "repo", NAME);
        answers.add(SUGGESTION);

        ingestor.ingest("repo", record("Quantum dots, revised"));

        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.REJECTED);
        assertThat(ClassifyingIngestor.unclassified(new Classification("repo", STAGING, "oai:repo:1", "t",
            Classification.Status.PENDING, SUGGESTION, null, false, null, null, null, null))).isFalse();
        assertThat(answers).as("the agent is not asked again").hasSize(1);
    }

    @Test
    void copiesTheFilesOfAPlacedRecordIntoItsItemOnce() {
        properties.getClassification().setAutoAccept(true);
        properties.getSources().get("repo").setUrl("https://repo.test/oai");
        properties.getSources().get("repo").setCopyFiles(true);
        final org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        final org.springframework.test.web.client.MockRestServiceServer source =
            org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        source.expect(org.springframework.test.web.client.ExpectedCount.once(),
                org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://repo.test/1.pdf"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                new byte[] {37, 80, 68, 70}, org.springframework.http.MediaType.APPLICATION_PDF));
        final RecordPlacer placer = new RecordPlacer(store, platform.topologies(), FakePlatform.clock(),
            new com.articulorium.harvest.ingest.FileCopier(builder.build(), properties.getFiles(),
                id -> com.articulorium.harvest.ingest.FileCopier.hostsOf(properties, id), delay -> { }, meters));
        final ClassifyingIngestor copying = new ClassifyingIngestor(new PlatformIngestor(harvester, paths,
            FakePlatform.clock()), harvester, com.articulorium.harvest.ingest.PlatformAuthorization.fixed("Bearer harvester"),
            paths, store, agent, placer, new TopologyProposer(agent, platform.topologies(), meters),
            properties.getClassification(), id -> SourcePolicy.of(properties, id), FakePlatform.clock(), meters);
        answers.add(preprint(null));

        copying.ingest("repo", record("Quantum dots"));
        // harvested again: the item is updated, its file is not fetched a second time
        assertThat(copying.ingest("repo", record("Quantum dots, revised"))).isEqualTo(IngestAction.UPDATED);

        final String item = store.read(harvester, STAGING).orElseThrow().placedAt();
        assertThat(platform.binaries()).hasSize(1).allSatisfy((path, file) -> {
            assertThat(path).startsWith(item + "/files/1-").endsWith(".pdf");
            assertThat(file).isEqualTo("application/pdf 4 1.pdf");
        });
        assertThat(title(item)).isEqualTo("Quantum dots, revised");
        source.verify();
    }

    @Test
    void namesProposedTopologiesAsTheTopologyApiWantsIds() {
        assertThat(TopologyProposer.idOf("Preprint")).isEqualTo("preprint");
        assertThat(TopologyProposer.idOf("  Conférence Paper (2026) ")).isEqualTo("conference-paper-2026");
        assertThat(TopologyProposer.idOf("_data_set")).isEqualTo("data_set");
        assertThat(TopologyProposer.idOf("???")).isEqualTo("harvested");
        assertThat(TopologyProposer.idOf("a".repeat(70))).hasSize(64);
    }

    @Test
    void readsTheAgentsTopologySuggestion() {
        final JsonMapper mapper = JsonMapper.builder().build();
        final TopologyProposal drafted = mapper.readValue("""
            {"recommendation": "NEW", "existing": null, "draft": %s, "errors": [], "rationale": "None fits.",
             "confidence": 0.7}
            """.formatted(PREPRINT_DRAFT), TopologyProposal.class);
        assertThat(drafted.recommendation()).isEqualTo(TopologyProposal.NEW);
        assertThat(drafted.draft().path("root").path("pcdmType").asString()).isEqualTo("Object");
        assertThat(drafted.errors()).isEmpty();

        final TopologyProposal existing = mapper.readValue("""
            {"recommendation": "EXISTING", "existing": {"id": "book", "name": "Book", "description": "Books",
             "builtIn": true, "rootLabel": "Book", "depth": 2}, "draft": null, "errors": [], "rationale": "A book.",
             "confidence": 0.9}
            """, TopologyProposal.class);
        assertThat(existing.existing()).isEqualTo(new TopologyProposal.Existing("book", "Book"));
        assertThat(existing.draft() == null || existing.draft().isNull()).isTrue();
    }

    @Test
    void createsTheProposedCollectionOnceAndPlacesRecordsInIt() {
        properties.getClassification().setAutoAccept(true);
        platform.put("communities/science",
            "<> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + Articulorium.COMMUNITY + "> .\n");
        final Suggestion datasets = new Suggestion(Suggestion.NEW_COLLECTION, null,
            new Suggestion.Ref("communities/science", "Science", "Science"), null,
            new Suggestion.NewStructure("Research Data", "Data sets of the faculty", null),
            new Suggestion.Topology("EXISTING", "thesis", "Thesis", null), 0.7, "No data collection yet.", List.of(), null);
        answers.add(datasets);
        answers.add(datasets);

        ingestor.ingest("repo", record("oai:repo:1", "Quantum dots"));
        ingestor.ingest("repo", record("oai:repo:2", "Quantum wells"));

        final String collection = "communities/science/research-data";
        final Model model = platform.get(collection);
        final Resource subject = model.createResource(FakePlatform.BASE + collection);
        final org.apache.jena.rdf.model.Property type = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        assertThat(subject.hasProperty(type, model.createResource(Articulorium.COLLECTION))).isTrue();
        assertThat(subject.hasProperty(type, model.createResource(Pcdm.COLLECTION))).isTrue();
        assertThat(title(collection)).isEqualTo("Research Data");
        assertThat(subject.hasProperty(model.createProperty(DCTERMS + "description"), "Data sets of the faculty")).isTrue();
        // the second record shares the collection the first one created
        assertThat(platform.exists(collection + "-2")).isFalse();
        for (final String identifier : List.of("oai:repo:1", "oai:repo:2")) {
            final Classification classification = store.read(harvester, PATHS.record("repo", identifier)).orElseThrow();
            assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
            assertThat(classification.decidedBy()).isEqualTo(ClassifyingIngestor.AUTO);
            assertThat(classification.placedAt()).startsWith(collection + "/");
        }
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "auto-accepted").count())
            .isEqualTo(2);
    }

    @Test
    void createsAProposedCommunityWithItsCollection() {
        properties.getClassification().setAutoAccept(true);
        answers.add(new Suggestion(Suggestion.NEW_COMMUNITY, null, null,
            new Suggestion.NewStructure("Physics", null, null), new Suggestion.NewStructure("Quantum Dots", null, "dots"),
            null, 0.6, "Nothing about physics yet.", List.of(), null));

        ingestor.ingest("repo", record("Quantum dots"));

        final Model community = platform.get("communities/physics");
        assertThat(community.contains(community.createResource(FakePlatform.BASE + "communities/physics"),
            community.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            community.createResource(Articulorium.COMMUNITY))).isTrue();
        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.ACCEPTED);
        // no topology suggested: a plain object in the new collection
        assertThat(classification.placedAt()).isEqualTo("communities/physics/dots/" + NAME);
    }

    @Test
    void leavesSuggestionsOutsideTheCommunityStructureForReview() {
        properties.getClassification().setAutoAccept(true);
        answers.add(new Suggestion(Suggestion.NEW_COLLECTION, null, new Suggestion.Ref("harvested/repo", "repo", "repo"),
            null, new Suggestion.NewStructure("Stolen", null, null), null, 0.99, null, List.of(), null));

        ingestor.ingest("repo", record("Quantum dots"));

        assertThat(store.read(harvester, STAGING).orElseThrow().status()).isEqualTo(Classification.Status.PENDING);
        assertThat(platform.exists("harvested/repo/stolen")).isFalse();
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "pending").count()).isEqualTo(1);
    }

    @Test
    void recordsAFailedClassificationWhenNoInstanceOfTheAgentIsRegistered() {
        // what Spring Cloud LoadBalancer throws when Consul knows no articulorium-ai instance
        final AgentClient unregistered = new AgentClient(org.springframework.web.client.RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                throw new IllegalStateException("No instances available for articulorium-ai");
            }).build(), "http://articulorium-ai/", com.articulorium.harvest.ingest.PlatformAuthorization.fixed(null));
        final ClassifyingIngestor withoutAgent = new ClassifyingIngestor(new PlatformIngestor(harvester, paths,
            FakePlatform.clock()), harvester, com.articulorium.harvest.ingest.PlatformAuthorization.fixed("Bearer harvester"),
            paths, store, unregistered, null, null, properties.getClassification(),
            source -> SourcePolicy.of(properties, source), FakePlatform.clock(), meters);

        assertThatThrownBy(() -> unregistered.classify("harvested/repo", "repo", record("Quantum dots")))
            .isInstanceOfSatisfying(AgentClient.AgentUnavailableException.class,
                e -> assertThat(e.getReason()).isEqualTo("AGENT_UNAVAILABLE"));
        // the record is ingested; its classification waits for a retry
        assertThat(withoutAgent.ingest("repo", record("Quantum dots"))).isEqualTo(IngestAction.CREATED);
        final Classification classification = store.read(harvester, STAGING).orElseThrow();
        assertThat(classification.status()).isEqualTo(Classification.Status.FAILED);
        assertThat(meters.counter("harvest.classifications", "source", "repo", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    void namesProposedStructuresAsTheUserInterfaceDoes() {
        assertThat(ClassifyingIngestor.slug(new Suggestion.NewStructure("Ångström Lab: Rare Books!", null, null)))
            .isEqualTo("angstrom-lab-rare-books");
        assertThat(ClassifyingIngestor.slug(new Suggestion.NewStructure("Rare Books", null, "rare"))).isEqualTo("rare");
        assertThat(ClassifyingIngestor.slug(new Suggestion.NewStructure("!!!", null, " "))).isEqualTo("untitled");
        assertThat(ClassifyingIngestor.slug(new Suggestion.NewStructure("a".repeat(100), null, null))).hasSize(80);
    }

    @Test
    void describesRecordsForTheAgent() {
        final AgentClient.Request request = AgentClient.request("harvested/repo", "repo", record("Quantum dots"));

        assertThat(request.titles()).containsExactly("Quantum dots");
        assertThat(request.subjects()).containsExactly("Quantum dots");
        assertThat(request.sets()).containsExactly("theses");
        assertThat(request.mediaTypes()).containsExactly("application/pdf");
        assertThat(request.context()).isEqualTo("harvested/repo");
        assertThat(Optional.of(AgentClient.title(record("T")))).contains("T");
    }

}
