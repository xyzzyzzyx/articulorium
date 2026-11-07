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
            properties.getClassification(), source -> properties.getSources().get(source).isClassify(), clock, meters);
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
            }, agent, null, properties.getClassification(), source -> true, FakePlatform.clock(), meters);

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
        assertThat(new HarvestProperties.Classification().isAutoAccept()).isTrue();
        assertThat(new HarvestProperties.Classification().getAutoAcceptThreshold()).isNull();
        assertThat(new HarvestProperties.Classification().getCommunities()).isEqualTo("communities");
    }

    @Test
    void neverAcceptsUncertainSuggestionsNewTopologiesOrNoPlacementAutomatically() {
        properties.getClassification().setAutoAccept(true);
        properties.getClassification().setAutoAcceptThreshold(0.9);
        final Suggestion.NewStructure proposed = new Suggestion.NewStructure("Data", null, null);
        final Suggestion.Ref science = new Suggestion.Ref("communities/science", "Science", "Science");

        assertThat(ingestor.autoAccept(SUGGESTION)).isTrue();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, null, 0.89, null, null, null))).isFalse();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, new Suggestion.Topology("NEW", null, "x", null), 0.99, null, null, null))).isFalse();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.NEW_COLLECTION, null, science, null, proposed, null,
            0.99, null, null, null))).isTrue();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.NEW_COLLECTION, null, null, null, proposed, null,
            0.99, null, null, null))).as("a new collection needs its community").isFalse();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.NEW_COMMUNITY, null, null, proposed, proposed, null,
            0.99, null, null, null))).isTrue();
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.NONE, null, null, null, null, null, 0.99, null, null,
            null))).isFalse();
        assertThat(ingestor.autoAccept(null)).isFalse();

        // without a threshold any confidence will do; turned off, nothing is accepted automatically
        properties.getClassification().setAutoAcceptThreshold(null);
        assertThat(ingestor.autoAccept(new Suggestion(Suggestion.EXISTING_COLLECTION, SUGGESTION.collection(), null,
            null, null, null, 0.1, null, null, null))).isTrue();
        properties.getClassification().setAutoAccept(false);
        assertThat(ingestor.autoAccept(SUGGESTION)).isFalse();
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
            paths, store, unregistered, null, properties.getClassification(), source -> true, FakePlatform.clock(), meters);

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
