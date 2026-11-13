package com.articulorium.ai.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.classification.Classification.Placement;
import com.articulorium.ai.classification.Classification.TopologyKind;
import com.articulorium.ai.support.Agents;
import com.articulorium.ai.support.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Tests the classification of harvested records against a mocked community structure.
 */
class ClassifierTest {

    private static final String TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";

    private static final String CONTAINS = "<http://www.w3.org/ns/ldp#contains>";

    private static final String COMMUNITY = "<https://articulorium.org/ns/repository#Community>";

    private static final String COLLECTION = "<https://articulorium.org/ns/repository#Collection>";

    private static final ClassificationRequest RECORD = new ClassificationRequest("harvested/repo", "repo", "oai:repo:1",
        List.of("Quantum dots in silicon"), List.of("A doctoral thesis on quantum dots."), List.of("Physics"),
        List.of("Doe, Jane"), List.of("2024"), List.of("Thesis"), List.of(), List.of("theses"),
        List.of("application/pdf"));

    private Agents agents;

    private Classifier classifier;

    @BeforeEach
    void setUp() {
        agents = new Agents();
        final StructureCatalog catalogs = new StructureCatalog(agents.reader, agents.topologies, agents.properties,
            Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC));
        classifier = new Classifier(agents.model, agents.access, catalogs, agents.properties);
        agents.platform.access("harvested/repo", true, "read", "write")
            .topologies("""
                [{"id": "book", "name": "Book", "description": "A monograph"},
                 {"id": "thesis", "name": "Thesis", "description": "A thesis with its PDF"}]
                """)
            .rdf("communities", """
                <> %s <http://platform.test/communities/science> .
                <> %s <http://platform.test/communities/private> .
                """.formatted(CONTAINS, CONTAINS))
            .rdf("communities/science", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Science" .
                <> %s <http://platform.test/communities/science/physics-theses> .
                <> %s <http://platform.test/communities/science/chemistry> .
                """.formatted(TYPE, COMMUNITY, CONTAINS, CONTAINS))
            .rdf("communities/science/physics-theses", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Physics theses" .
                <> <http://purl.org/dc/terms/subject> "Condensed matter" .
                """.formatted(TYPE, COLLECTION))
            .rdf("communities/science/chemistry", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Chemistry" .
                <> <http://purl.org/dc/terms/description> "Papers of the chemistry department" .
                """.formatted(TYPE, COLLECTION))
            .refused("communities/private", HttpStatus.FORBIDDEN);
    }

    @Test
    void placesARecordInAnExistingCollectionWithItsTopology() {
        agents.chat.answer("""
            {"placement": "EXISTING_COLLECTION", "collection": "c2", "topologyDecision": "EXISTING",
             "topology": "thesis", "confidence": 0.93, "rationale": "A physics thesis.",
             "alternatives": [{"collection": "c1", "confidence": 0.1}, {"collection": "c2", "confidence": 0.9},
                              {"collection": "c7", "confidence": 0.5}]}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(classification.collection())
            .isEqualTo(new Classification.Ref("communities/science/physics-theses", "Physics theses",
                "Science > Physics theses"));
        assertThat(classification.topology()).isEqualTo(new Classification.TopologyChoice(TopologyKind.EXISTING,
            "thesis", "Thesis", null));
        assertThat(classification.confidence()).isEqualTo(0.93);
        // the chosen collection and unknown ids are no alternatives
        assertThat(classification.alternatives()).extracting(alternative -> alternative.collection().path())
            .containsExactly("communities/science/chemistry");
        assertThat(classification.model()).startsWith("scripted");
        final String user = agents.chat.prompts().get(0).getUserMessage().getText();
        assertThat(user).contains("m1 community Science")
            .contains("c1 collection Science > Chemistry — Papers of the chemistry department")
            .contains("c2 collection Science > Physics theses [Condensed matter]")
            .contains("thesis: Thesis — A thesis with its PDF")
            .contains("title: Quantum dots in silicon").contains("sets in its source: theses")
            .doesNotContain("private");
    }

    @Test
    void proposesANewCollectionInAnExistingCommunity() {
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m1",
             "newCollection": {"title": "Theses (Physics)", "description": "Doctoral theses"},
             "topologyDecision": "NEW", "newTopology": {"title": "Thesis with data", "description": "PDF and data"},
             "confidence": 0.6, "rationale": "No collection of theses."}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(classification.community().path()).isEqualTo("communities/science");
        assertThat(classification.newCollection())
            .isEqualTo(new Classification.NewStructure("Theses (Physics)", "Doctoral theses", "theses-physics"));
        assertThat(classification.topology()).isEqualTo(new Classification.TopologyChoice(TopologyKind.NEW, null,
            "Thesis with data", "PDF and data"));
    }

    @Test
    void proposesANewTopLevelCommunity() {
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "newCommunity": {"title": "Humanities"},
             "newCollection": {"title": "Theses"}, "topologyDecision": "NONE", "confidence": 0.4}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).isNull();
        assertThat(classification.newCommunity().slug()).isEqualTo("humanities");
        assertThat(classification.newCollection().title()).isEqualTo("Theses");
        assertThat(classification.topology().kind()).isEqualTo(TopologyKind.NONE);
    }

    @Test
    void distrustsAnswersNamingWhatTheModelWasNotShown() {
        agents.chat.answer("""
            {"placement": "EXISTING_COLLECTION", "collection": "c42", "topologyDecision": "EXISTING",
             "topology": "atlas", "confidence": 0.99}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NONE);
        assertThat(classification.confidence()).isZero();
        assertThat(classification.topology().kind()).isEqualTo(TopologyKind.NONE);
    }

    @Test
    void distrustsNewCollectionsWithoutACommunity() {
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "c2", "newCollection": {"title": "Theses"}, "confidence": 0.8}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).placement()).isEqualTo(Placement.NONE);
    }

    /** Starts over with a repository that has no community yet. */
    private void emptyRepository() {
        agents = new Agents();
        classifier = new Classifier(agents.model, agents.access, new StructureCatalog(agents.reader, agents.topologies,
            agents.properties, Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC)), agents.properties);
        agents.platform.access("harvested/repo", true, "read", "write")
            .topologies("[{\"id\": \"book\", \"name\": \"Book\", \"description\": \"A monograph\"}]")
            .rdf("communities", "<> <http://purl.org/dc/terms/title> \"Communities\" .\n");
    }

    @Test
    void startsTheStructureOfAnEmptyRepository() {
        emptyRepository();
        // a small model copies the id of the schema's example although no community exists
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "community": "m2", "collection": "c3",
             "newCommunity": {"title": "Physics", "description": "Physical sciences"},
             "newCollection": {"title": "Theses"}, "topologyDecision": "NONE", "confidence": 0.7}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).as("the unknown parent is dropped: a top-level community").isNull();
        assertThat(classification.newCommunity().title()).isEqualTo("Physics");
        assertThat(classification.newCollection().slug()).isEqualTo("theses");
        assertThat(classification.confidence()).isEqualTo(0.7);
        assertThat(agents.chat.prompts()).hasSize(1);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("There are no communities or collections yet, so answer NEW_COMMUNITY");
    }

    @Test
    void tellsTheModelWhichIdsExist() {
        agents.chat.answer("""
            {"placement": "NONE", "topologyDecision": "NONE", "confidence": 0.1}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).placement()).isEqualTo(Placement.NONE);
        assertThat(agents.chat.prompts()).as("NONE is an answer, not a mistake to correct").hasSize(1);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("The only community ids are m1; the only collection ids are c1, c2.");
    }

    @Test
    void keepsAProposalThatStandsWithoutTheUnknownId() {
        // an "existing" collection that does not exist, with the proposal of a new one in a real community
        agents.chat.answer("""
            {"placement": "EXISTING_COLLECTION", "collection": "c9", "community": "m1",
             "newCollection": {"title": "Theses"}, "confidence": 0.8}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m7", "newCommunity": {"title": "Humanities"},
             "newCollection": {"title": "Theses"}, "confidence": 0.6}
            """);

        final Classification inScience = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inScience.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(inScience.community().path()).isEqualTo("communities/science");
        assertThat(inScience.newCommunity()).isNull();

        final Classification inHumanities = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inHumanities.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(inHumanities.community()).isNull();
        assertThat(inHumanities.newCommunity().title()).isEqualTo("Humanities");
        assertThat(agents.chat.prompts()).hasSize(2);
    }

    @Test
    void asksTheModelOnceToCorrectAnAnswerThatCannotBeUsed() {
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m9", "newCollection": {"title": "Theses"}, "confidence": 0.8}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1", "newCollection": {"title": "Theses"}, "confidence": 0.8}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(classification.community().path()).isEqualTo("communities/science");
        assertThat(classification.confidence()).isEqualTo(0.8);
        assertThat(agents.chat.prompts()).hasSize(2);
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("community \"m9\" is not one of the listed community ids")
            .contains("Answer again without this problem.");
    }

    @Test
    void namesCommunityAndCollectionAlikeWhenAnEmptyRepositoryGetsOneTitleOnly() {
        emptyRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "newCollection": {"title": "Digital Libraries"}, "confidence": 0.5}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(agents.chat.prompts()).as("repaired without asking the model again").hasSize(1);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.newCommunity().title()).isEqualTo("Digital Libraries");
        assertThat(classification.newCollection().title()).isEqualTo("Digital Libraries");
        assertThat(classification.confidence()).isEqualTo(0.5);
    }

    @Test
    void asksAgainWhenTheAnswerCannotBeRead() {
        agents.chat.answer("I think this is a thesis.", """
            {"placement": "EXISTING_COLLECTION", "collection": "c2", "topologyDecision": "NONE", "confidence": 0.9}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).placement())
            .isEqualTo(Placement.EXISTING_COLLECTION);

        agents.chat.answer("no", "still no");
        assertThatThrownBy(() -> classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_OUTPUT));
    }

    @Test
    void asksTheModelTwiceAtMost() {
        // an unreadable answer, then one that cannot be used: no third call to correct it
        agents.chat.answer("I think this is a thesis.", """
            {"placement": "NEW_COLLECTION", "community": "m9", "newCollection": {"title": "Theses"}, "confidence": 0.8}
            """, """
            {"placement": "EXISTING_COLLECTION", "collection": "c2", "confidence": 0.9}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NONE);
        assertThat(classification.confidence()).isZero();
        assertThat(agents.chat.prompts()).hasSize(2);
    }

    @Test
    void takesNoSubjectForATopology() {
        agents.chat.answer("""
            {"placement": "EXISTING_COLLECTION", "collection": "c2", "topologyDecision": "NEW",
             "newTopology": {"title": " physics ", "description": "Research about physics"}, "confidence": 0.9}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1", "newCollection": {"title": "Quantum Dots"},
             "topologyDecision": "NEW", "newTopology": {"title": "Quantum dots"}, "confidence": 0.9}
            """, """
            {"placement": "EXISTING_COLLECTION", "collection": "c2", "topologyDecision": "NEW",
             "newTopology": {"title": "Thesis with data sets"}, "confidence": 0.9}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).topology().kind())
            .as("a subject of the record").isEqualTo(TopologyKind.NONE);
        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).topology().kind())
            .as("the proposed collection").isEqualTo(TopologyKind.NONE);
        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).topology())
            .isEqualTo(new Classification.TopologyChoice(TopologyKind.NEW, null, "Thesis with data sets", null));
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("never what it is about");
    }

    @Test
    void needsWriteAccessToTheContext() {
        agents.platform.access("harvested/other", true, "read");
        final ClassificationRequest request = new ClassificationRequest("harvested/other", "other", "x", List.of("t"),
            null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> classifier.classify(request, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void needsSomethingToClassify() {
        final ClassificationRequest empty = new ClassificationRequest("harvested/repo", "repo", "x", null, null, null,
            null, null, null, null, null, null);

        assertThatThrownBy(() -> classifier.classify(empty, Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_REQUEST));
    }

}
