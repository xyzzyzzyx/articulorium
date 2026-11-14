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
            {"placement": "NEW_COMMUNITY", "newCommunities": [{"title": "Humanities"}],
             "newCollection": {"title": "Theses"}, "topologyDecision": "NONE", "confidence": 0.4}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).isNull();
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::slug)
            .containsExactly("humanities");
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
             "newCommunities": {"title": "Physics", "description": "Physical sciences"},
             "newCollection": {"title": "Theses"}, "topologyDecision": "NONE", "confidence": 0.7}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).as("the unknown parent is dropped: a top-level community").isNull();
        assertThat(classification.newCommunities()).as("a single object is one level")
            .extracting(Classification.NewStructure::title).containsExactly("Physics");
        assertThat(classification.newCollection().slug()).isEqualTo("theses");
        assertThat(classification.confidence()).isEqualTo(0.7);
        assertThat(agents.chat.prompts()).hasSize(1);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("There are no communities or collections yet, so answer NEW_COMMUNITY");
    }

    @Test
    void proposesCommunitiesFromGeneralToSpecific() {
        emptyRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY",
             "newCommunities": [{"title": "Sciences"}, {"title": " "}, {"title": "Computer Science"},
                                {"title": "computer science"}],
             "newCollection": {"title": "Digital Libraries"}, "confidence": 0.7}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).isNull();
        // untitled and repeated levels are dropped
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::slug)
            .containsExactly("sciences", "computer-science");
        assertThat(classification.newCollection().title()).isEqualTo("Digital Libraries");
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText()).contains("from the broadest field down");
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("top-level communities are broad fields")
            .contains("Never propose a narrow topic as a top-level community.");
    }

    /** Starts over with Sciences > Computer Science > Digital Libraries (a collection). */
    private void nestedRepository() {
        agents = new Agents();
        classifier = new Classifier(agents.model, agents.access, new StructureCatalog(agents.reader, agents.topologies,
            agents.properties, Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC)), agents.properties);
        agents.platform.access("harvested/repo", true, "read", "write")
            .topologies("[]")
            .rdf("communities", "<> %s <http://platform.test/communities/sciences> .\n".formatted(CONTAINS))
            .rdf("communities/sciences", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Sciences" .
                <> %s <http://platform.test/communities/sciences/computer-science> .
                """.formatted(TYPE, COMMUNITY, CONTAINS))
            .rdf("communities/sciences/computer-science", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Computer Science" .
                <> %s <http://platform.test/communities/sciences/computer-science/digital-libraries> .
                """.formatted(TYPE, COMMUNITY, CONTAINS))
            .rdf("communities/sciences/computer-science/digital-libraries", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Digital Libraries" .
                """.formatted(TYPE, COLLECTION));
    }

    @Test
    void placesProposedLevelsThatExistOnTheExistingStructure() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY",
             "newCommunities": [{"title": "sciences"}, {"title": "Computer Science"}, {"title": "Machine Learning"}],
             "newCollection": {"title": "Preprints"}, "confidence": 0.7}
            """, """
            {"placement": "NEW_COMMUNITY", "newCommunities": [{"title": "Sciences"}, {"title": "Computer science"}],
             "newCollection": {"title": "Digital libraries"}, "confidence": 0.8}
            """, """
            {"placement": "NEW_COMMUNITY", "newCommunities": [{"title": "Sciences"}, {"title": "Mathematics"}],
             "newCollection": {"title": "Preprints"}, "confidence": 0.6}
            """);

        final Classification deeper = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(deeper.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(deeper.community().path()).isEqualTo("communities/sciences/computer-science");
        assertThat(deeper.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Machine Learning");

        final Classification existing = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(existing.placement()).as("the whole path exists").isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(existing.collection().path()).isEqualTo("communities/sciences/computer-science/digital-libraries");
        assertThat(existing.newCommunities()).isNull();
        assertThat(existing.newCollection()).isNull();

        final Classification sibling = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(sibling.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(sibling.community().path()).isEqualTo("communities/sciences");
        assertThat(sibling.newCommunities()).extracting(Classification.NewStructure::title).containsExactly("Mathematics");
        assertThat(agents.chat.prompts()).hasSize(3);
    }

    @Test
    void readsTitlesWrittenAsPathsAsLevels() {
        nestedRepository();
        // as the model saw the structure ("Sciences > Computer Science"), written from the top again
        // inside Computer Science, repeating a community above, the collection a path too
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "community": "m2",
             "newCommunities": [{"title": "Sciences > Computer Science > Sciences > Machine Learning",
                                 "description": "Learning from data"}],
             "newCollection": {"title": "Machine Learning › Ranking models"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1",
             "newCollection": {"title": "Computer Science > Digital Libraries"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community().path()).isEqualTo("communities/sciences/computer-science");
        assertThat(classification.newCommunities())
            .containsExactly(new Classification.NewStructure("Machine Learning", "Learning from data", "machine-learning"));
        assertThat(classification.newCollection().title()).isEqualTo("Ranking models");

        final Classification existing = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(existing.placement()).as("the path names an existing collection").isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(existing.collection().path()).isEqualTo("communities/sciences/computer-science/digital-libraries");
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("never write a path or \">\" in a title");
    }

    @Test
    void splitsPathsOnlyAtTheirSeparators() {
        assertThat(Classifier.levelsOf("Sciences > Computer Science")).containsExactly("Sciences", "Computer Science");
        assertThat(Classifier.levelsOf("Sciences›Physics » Optics → Lasers / Diodes"))
            .containsExactly("Sciences", "Physics", "Optics", "Lasers", "Diodes");
        assertThat(Classifier.levelsOf("Input/Output")).containsExactly("Input/Output");
        assertThat(Classifier.levelsOf(" > Physics > ")).containsExactly("Physics");
    }

    @Test
    void reusesACommunityOfTheSameNameInAnotherBranch() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY",
             "newCommunities": [{"title": "Engineering"}, {"title": "Computer sciences"}, {"title": "Machine Learning"}],
             "newCollection": {"title": "Preprints"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        // Computer Science exists (below Sciences): it is not created again below a new Engineering
        assertThat(classification.community().path()).isEqualTo("communities/sciences/computer-science");
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Machine Learning");
    }

    @Test
    void placesInTheCollectionAProposedCommunityNames() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "community": "m2", "newCommunities": [{"title": "Digital Library"}],
             "newCollection": {"title": "Preprints"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        // Computer Science holds a collection of that name: no community of the same name beside it
        assertThat(classification.placement()).isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(classification.collection().path()).isEqualTo("communities/sciences/computer-science/digital-libraries");
    }

    @Test
    void replacesACollectionNamedAfterTheRecordWithoutAskingAgain() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m2",
             "newCollection": {"title": "New Collection for Quantum Dots in Silicon"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COMMUNITY", "community": "m1",
             "newCommunities": [{"title": "Physics"}, {"title": "Condensed Matter"}],
             "newCollection": {"title": "Quantum dots in silicon"}, "confidence": 0.6}
            """);

        final Classification inCommunity = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inCommunity.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(inCommunity.community().path()).isEqualTo("communities/sciences/computer-science");
        assertThat(inCommunity.newCollection().title()).as("named by its community").isEqualTo("Computer Science");

        final Classification inNewCommunities = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        // the most specific community names the collection, and is not created twice
        assertThat(inNewCommunities.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Physics");
        assertThat(inNewCommunities.newCollection().title()).isEqualTo("Condensed Matter");
        assertThat(agents.chat.prompts()).as("repaired without the model").hasSize(2);
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("never name a collection after the record").contains("never a classification code");
    }

    @Test
    void neverProposesClassificationCodesAsCommunities() {
        emptyRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "newCommunities": [{"title": "H.3.7"}, {"title": "Computer Science"},
             {"title": "cs.DL"}], "newCollection": {"title": "Digital Libraries"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Computer Science");
    }

    @Test
    void keepsTheMoreGeneralLevelsOfALongPath() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COMMUNITY", "community": "m2",
             "newCommunities": [{"title": "Computer Science"}, {"title": "Information Retrieval"},
                                {"title": "Ranking"}, {"title": "Neural Ranking"}, {"title": "Dense Retrieval"},
                                {"title": "Passage Retrieval"}],
             "newCollection": {"title": "Passage retrieval"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1", "newCommunities": [{"title": "Physics"}],
             "newCollection": {"title": "Computer Science"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.community().path()).isEqualTo("communities/sciences/computer-science");
        // the parent repeated, the collection's twin and levels beyond two are dropped
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Information Retrieval", "Ranking");
        assertThat(classification.newCollection().title()).isEqualTo("Passage retrieval");

        final Classification newCollection = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(newCollection.placement()).as("a new collection: communities besides are not wanted")
            .isEqualTo(Placement.NEW_COLLECTION);
        assertThat(newCollection.community().path()).isEqualTo("communities/sciences");
        assertThat(newCollection.newCommunities()).isNull();
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
            {"placement": "NEW_COLLECTION", "community": "m7", "newCommunities": [{"title": "Humanities"}],
             "newCollection": {"title": "Theses"}, "confidence": 0.6}
            """);

        final Classification inScience = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inScience.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(inScience.community().path()).isEqualTo("communities/science");
        assertThat(inScience.newCommunities()).isNull();

        final Classification inHumanities = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inHumanities.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(inHumanities.community()).isNull();
        assertThat(inHumanities.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Humanities");
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
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Digital Libraries");
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
