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
    void proposesADisciplineOfAFieldWhenNoCommunityFits() {
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "Humanities", "discipline": {"title": "New Community: History",
             "description": "The study of the past"}, "newCollection": {"title": "\\"Theses\\""},
             "topologyDecision": "NONE", "confidence": 0.4}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).isNull();
        // the field as configured, the discipline without its label, the collection without quotes
        assertThat(classification.newCommunities()).containsExactly(
            new Classification.NewStructure("Humanities and the Arts", null, "humanities-and-the-arts"),
            new Classification.NewStructure("History", "The study of the past", "history"));
        assertThat(classification.newCollection().title()).isEqualTo("Theses");
        assertThat(classification.topology().kind()).isEqualTo(TopologyKind.NONE);
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("Only these fields may exist: Natural Sciences; Engineering and Technology;")
            .contains("no prefix such as \"New Community\"");
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
        // a small model copies the ids of the schema's example although nothing exists
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m2", "collection": "c3", "field": "Natural Sciences",
             "discipline": {"title": "Physics"}, "newCollection": {"title": "Quantum Devices"},
             "topologyDecision": "NONE", "confidence": 0.7}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.community()).isNull();
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Physics");
        assertThat(classification.newCollection().slug()).isEqualTo("quantum-devices");
        assertThat(classification.confidence()).isEqualTo(0.7);
        assertThat(agents.chat.prompts()).hasSize(1);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("There are no communities or collections yet, so answer NEW_COLLECTION with field, discipline");
    }

    @Test
    void putsCollectionsInDisciplinesNotInFields() {
        emptyRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "Natural Sciences", "newCollection": {"title": "Quantum Devices"},
             "confidence": 0.7}
            """, """
            {"placement": "NEW_COLLECTION", "field": "Natural Sciences", "discipline": {"title": "Physics"},
             "newCollection": {"title": "Quantum Devices"}, "confidence": 0.7}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Physics");
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("a collection goes in a discipline (a community inside a field), not directly in the field");
    }

    /** Starts over with Natural Sciences (a field) > Computer Science > Digital Libraries (a collection). */
    private void nestedRepository() {
        agents = new Agents();
        classifier = new Classifier(agents.model, agents.access, new StructureCatalog(agents.reader, agents.topologies,
            agents.properties, Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC)), agents.properties);
        agents.platform.access("harvested/repo", true, "read", "write")
            .topologies("[]")
            .rdf("communities", "<> %s <http://platform.test/communities/natural-sciences> .\n".formatted(CONTAINS))
            .rdf("communities/natural-sciences", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Natural Sciences" .
                <> %s <http://platform.test/communities/natural-sciences/computer-science> .
                """.formatted(TYPE, COMMUNITY, CONTAINS))
            .rdf("communities/natural-sciences/computer-science", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Computer Science" .
                <> %s <http://platform.test/communities/natural-sciences/computer-science/digital-libraries> .
                """.formatted(TYPE, COMMUNITY, CONTAINS))
            .rdf("communities/natural-sciences/computer-science/digital-libraries", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Digital Libraries" .
                """.formatted(TYPE, COLLECTION));
    }

    @Test
    void usesTheFieldsAndDisciplinesThatExist() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "natural sciences", "discipline": {"title": "Computer Science"},
             "newCollection": {"title": "Preprints"}, "confidence": 0.7}
            """, """
            {"placement": "NEW_COLLECTION", "field": "Natural Sciences", "discipline": {"title": "Computer sciences"},
             "newCollection": {"title": "Digital library"}, "confidence": 0.8}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1", "discipline": {"title": "Mathematics"},
             "newCollection": {"title": "Preprints"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "field": "Engineering", "discipline": {"title": "Computer Science"},
             "newCollection": {"title": "Preprints"}, "confidence": 0.6}
            """);

        final Classification existing = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(existing.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(existing.community().path()).isEqualTo("communities/natural-sciences/computer-science");
        assertThat(existing.newCommunities()).isNull();

        final Classification collection = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(collection.placement()).as("the whole path exists").isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(collection.collection().path())
            .isEqualTo("communities/natural-sciences/computer-science/digital-libraries");

        final Classification inField = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inField.placement()).as("a new discipline of the chosen field").isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(inField.community().path()).isEqualTo("communities/natural-sciences");
        assertThat(inField.newCommunities()).extracting(Classification.NewStructure::title).containsExactly("Mathematics");

        final Classification elsewhere = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(elsewhere.placement()).as("a discipline exists once, in whichever field")
            .isEqualTo(Placement.NEW_COLLECTION);
        assertThat(elsewhere.community().path()).isEqualTo("communities/natural-sciences/computer-science");
        assertThat(agents.chat.prompts()).hasSize(4);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText()).contains("m1 field Natural Sciences")
            .contains("m2 community Natural Sciences > Computer Science");
    }

    @Test
    void readsTitlesWrittenAsPathsAsLevels() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "discipline": {"title": "Natural Sciences > Computer Science"},
             "newCollection": {"title": "New Collection: Machine Learning"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1",
             "newCollection": {"title": "Computer Science > Digital Libraries"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(classification.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(classification.community().path()).isEqualTo("communities/natural-sciences/computer-science");
        assertThat(classification.newCollection().title()).isEqualTo("Machine Learning");

        final Classification existing = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(existing.placement()).as("the path names an existing collection").isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(existing.collection().path()).isEqualTo("communities/natural-sciences/computer-science/digital-libraries");
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
    void findsTheFieldANameStandsFor() {
        final List<String> fields = agents.properties.getClassification().getFields();

        assertThat(Classifier.matchField("Humanities", fields)).isEqualTo("Humanities and the Arts");
        assertThat(Classifier.matchField("health sciences", fields)).isEqualTo("Medical and Health Sciences");
        assertThat(Classifier.matchField("Engineering & Technology", fields)).isEqualTo("Engineering and Technology");
        assertThat(Classifier.matchField("Sciences", fields)).as("several fields").isNull();
        assertThat(Classifier.matchField("Computer Science", fields)).isNull();
        assertThat(Classifier.matchField(null, fields)).isNull();
    }

    @Test
    void asksAgainForACollectionNamedAfterTheRecord() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m2",
             "newCollection": {"title": "New Collection for Quantum Dots in Silicon"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m2", "newCollection": {"title": "Quantum Computing"},
             "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.newCollection().title()).isEqualTo("Quantum Computing");
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText()).contains("names this one record");
    }

    @Test
    void neverNamesANewCollectionLikeItsCommunityOrAField() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "community": "m2", "newCollection": {"title": "Computer sciences"},
             "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m2", "newCollection": {"title": "Natural Sciences"},
             "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m2", "newCollection": {"title": "Digital Preservation"},
             "confidence": 0.6}
            """);

        final Classification refused = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(refused.placement()).as("for a person to decide").isEqualTo(Placement.NONE);
        assertThat(refused.confidence()).isZero();
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("is named like the community it goes in");

        final Classification placed = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(placed.newCollection().title()).isEqualTo("Digital Preservation");
        assertThat(agents.chat.prompts()).hasSize(3);
    }

    @Test
    void neverProposesClassificationCodes() {
        nestedRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "Natural Sciences", "discipline": {"title": "H.3.7"},
             "newCollection": {"title": "Digital Archives"}, "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m2", "newCollection": {"title": "cs.DL"}, "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NONE);
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("\"H.3.7\" is a classification code, not a name");
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
            {"placement": "NEW_COLLECTION", "community": "m7", "field": "Humanities and the Arts",
             "discipline": {"title": "History"}, "newCollection": {"title": "Theses"}, "confidence": 0.6}
            """);

        final Classification inScience = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inScience.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(inScience.community().path()).isEqualTo("communities/science");
        assertThat(inScience.newCommunities()).isNull();

        final Classification inHumanities = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inHumanities.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(inHumanities.community()).isNull();
        assertThat(inHumanities.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Humanities and the Arts", "History");
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
