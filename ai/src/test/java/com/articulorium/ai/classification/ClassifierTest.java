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

    /** A record with files of several kinds: an item of parts, for which a new topology can make sense. */
    private static final ClassificationRequest RECORD_WITH_DATA = new ClassificationRequest("harvested/repo", "repo",
        "oai:repo:1", RECORD.titles(), RECORD.descriptions(), RECORD.subjects(), RECORD.creators(), RECORD.dates(),
        RECORD.genres(), RECORD.types(), RECORD.sets(), List.of("application/pdf", "text/csv"));

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
             "topology": "thesis", "confidence": 0.93, "rationale": "A physics thesis."}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(classification.collection())
            .isEqualTo(new Classification.Ref("communities/science/physics-theses", "Physics theses",
                "Science > Physics theses"));
        assertThat(classification.topology()).isEqualTo(new Classification.TopologyChoice(TopologyKind.EXISTING,
            "thesis", "Thesis", null));
        assertThat(classification.confidence()).isEqualTo(0.93);
        assertThat(classification.alternatives()).isEmpty();
        assertThat(classification.model()).startsWith("scripted");
        final String user = agents.chat.prompts().get(0).getUserMessage().getText();
        assertThat(user).contains("m1 community Science")
            .contains("c1 collection Science > Chemistry — Papers of the chemistry department")
            .contains("c2 collection Science > Physics theses [Condensed matter]")
            .contains("thesis: Thesis — A thesis with its PDF")
            .contains("title: Quantum dots in silicon")
            // set specs are the source's own codes, no names for this repository
            .doesNotContain("sets in its source")
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

        final Classification classification = classifier.classify(RECORD_WITH_DATA, Platform.TOKEN, Locale.ENGLISH);

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
            .contains("Only these fields exist: Natural Sciences; Engineering and Technology;")
            .contains("no prefix such as \"New Collection\"");
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
            {"collection": "c3", "field": "Natural Sciences", "discipline": "Physics", "topic": "Quantum Devices",
             "topology": "", "confidence": 0.7}
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
            .contains("There are no collections yet: give field, discipline and topic.");
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
            .contains("discipline is missing: give the discipline");
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
            {"field": "Natural Sciences", "discipline": "Mathematics", "topic": "Preprints", "confidence": 0.6}
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
    void readsTheShapesSmallModelsWrite() {
        emptyRepository();
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "Social Sciences", "discipline": "Information Science",
             "newCollection": "Social Sciences: Bibliometric Analysis", "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "community": "Information Science", "collection": "Citation Analysis",
             "field": "Social Sciences", "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "field": "Social Sciences", "discipline": "Information Science",
             "newCollection": "Fedora: An Architecture for Complex Objects", "confidence": 0.6}
            """);

        // titles as strings; "Field: Name" is a path
        final Classification strings = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(strings.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(strings.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Social Sciences", "Information Science");
        assertThat(strings.newCollection().title()).isEqualTo("Bibliometric Analysis");

        // titles where ids go
        final Classification inIdSlots = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(inIdSlots.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Social Sciences", "Information Science");
        assertThat(inIdSlots.newCollection().title()).isEqualTo("Citation Analysis");
        assertThat(agents.chat.prompts()).hasSize(2);

        // a colon after anything else starts a subtitle: the title of one record
        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).placement()).isEqualTo(Placement.NONE);
    }

    @Test
    void asksOnceMoreWhenTheModelPlacesNothing() {
        agents.chat.answer("""
            {"placement": "NONE", "confidence": 0, "rationale": "Nothing fits."}
            """, """
            {"placement": "NEW_COLLECTION", "community": "m1", "newCollection": "Quantum Devices", "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COLLECTION);
        assertThat(classification.newCollection().title()).isEqualTo("Quantum Devices");
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("topic (the new collection's title) is missing");
    }

    @Test
    void takesNoneWithAProposalForANewCollection() {
        agents.chat.answer("""
            {"placement": "NONE", "field": "Natural Sciences", "discipline": "Physics",
             "newCollection": "Quantum Devices", "confidence": 0.5}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Physics");
        assertThat(agents.chat.prompts()).hasSize(1);
    }

    @Test
    void neverTakesNoneForAName() {
        agents = new Agents();
        classifier = new Classifier(agents.model, agents.access, new StructureCatalog(agents.reader, agents.topologies,
            agents.properties, Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC)), agents.properties);
        agents.platform.access("harvested/repo", true, "read", "write").topologies("[]")
            .rdf("communities", """
                <> %s <http://platform.test/communities/social-sciences> .
                """.formatted(CONTAINS))
            .rdf("communities/social-sciences", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "Social Sciences" .
                <> %s <http://platform.test/communities/social-sciences/none> .
                """.formatted(TYPE, COMMUNITY, CONTAINS))
            .rdf("communities/social-sciences/none", """
                <> %s %s .
                <> <http://purl.org/dc/terms/title> "None" .
                """.formatted(TYPE, COMMUNITY));
        agents.chat.answer("""
            {"placement": "NEW_COLLECTION", "field": "Social Sciences", "discipline": "None",
             "newCollection": "Research Methods", "confidence": 0.6}
            """, """
            {"placement": "NEW_COLLECTION", "field": "Social Sciences", "discipline": "Sociology",
             "newCollection": "Social Sciences > N/A > Research Methods", "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.community().path()).isEqualTo("communities/social-sciences");
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Sociology");
        assertThat(classification.newCollection().title()).isEqualTo("Research Methods");
        // "None" is a mistake of an earlier model: not offered as a community
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText()).contains("m1 field Social Sciences")
            .doesNotContain("None");
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("discipline is missing: give the discipline");
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
            .contains("is named like its discipline");

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
        // a code is no discipline: the model is asked for one
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("discipline is missing: give the discipline");
    }

    @Test
    void tellsTheModelWhichIdsExist() {
        agents.chat.answer("""
            {"placement": "NONE", "topologyDecision": "NONE", "confidence": 0.1}
            """, """
            {"placement": "NONE", "topologyDecision": "NONE", "confidence": 0.1}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).placement()).isEqualTo(Placement.NONE);
        assertThat(agents.chat.prompts()).as("asked once to correct it, then it stands").hasSize(2);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("The only collection ids are c1, c2: give one of them as collection only when that collection "
                + "fits the record, otherwise give field, discipline and topic.");
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
            .contains("discipline is missing: give the discipline")
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
        assertThat(classifier.classify(RECORD_WITH_DATA, Platform.TOKEN, Locale.ENGLISH).topology())
            .isEqualTo(new Classification.TopologyChoice(TopologyKind.NEW, null, "Thesis with data sets", null));
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText())
            .contains("a new topology only for an item made of files of several kinds");
    }

    /** An arXiv record: its subjects name its discipline and topic, besides an ACM code. */
    private static final ClassificationRequest ARXIV_RECORD = new ClassificationRequest("harvested/repo", "repo",
        "oai:arXiv.org:1", List.of("MapHub: A Historical Map Annotation Platform"),
        List.of("A platform to annotate historical maps."), List.of("Computer Science - Digital Libraries", "H.3.7"),
        List.of("Doe, Jane"), List.of("2014"), List.of(), List.of(), List.of(), List.of("application/pdf"));

    /** A recent arXiv record: plain category names as subjects, the discipline in its set. */
    private static final ClassificationRequest ARXIV_CS_RECORD = new ClassificationRequest("harvested/repo", "repo",
        "oai:arXiv.org:2611.17233", List.of("SAIL: Scientific Agentic Intelligence via a Science-Aware Loop"),
        List.of("An agent for scientific discovery."),
        List.of("Information Retrieval", "Computation and Language", "Digital Libraries", "H.3.7"),
        List.of("Doe, Jane"), List.of("2026-11-12"), List.of(), List.of("text"), List.of("cs:cs:DL"),
        List.of("application/pdf"));

    @Test
    void takesTheDisciplineAndTopicTheRecordIsClassifiedIn() {
        emptyRepository();
        // the field repeated as discipline and a collection named after the record: the record's own
        // classification stands in, without asking the model again
        agents.chat.answer("""
            {"collection": "", "field": "Natural Sciences", "discipline": "Natural Sciences",
             "topic": "New Collection for MapHub", "confidence": 0.6}
            """, """
            {"field": "Natural Sciences", "discipline": "Computer Science", "topic": "Map Annotation",
             "confidence": 0.9}
            """);

        final Classification classified = classifier.classify(ARXIV_RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(classified.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classified.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Computer Science");
        assertThat(classified.newCollection().title()).isEqualTo("Digital Libraries");
        assertThat(classified.confidence()).as("at least the configured confidence").isEqualTo(0.7);
        assertThat(classified.rationale()).startsWith("Placed by the record's own classification: discipline "
            + "Computer Science (subject \"Computer Science - Digital Libraries\"), topic Digital Libraries");
        assertThat(agents.chat.prompts()).hasSize(1);

        // the source's classification goes before the model's own topic, so records like it meet
        final Classification topic = classifier.classify(ARXIV_RECORD, Platform.TOKEN, Locale.ENGLISH);
        assertThat(topic.newCollection().title()).isEqualTo("Digital Libraries");
        assertThat(topic.confidence()).isEqualTo(0.9);
    }

    @Test
    void placesARecordInTheDisciplineOfItsSetAndTheCollectionOfItsPrimarySubject() {
        emptyRepository();
        // what the model answered for every recent arXiv record: a field, nothing else
        agents.chat.answer("""
            {"collection": "", "field": "Engineering and Technology", "discipline": "", "topic": "",
             "confidence": 0, "rationale": "The subjects suggest Engineering and Technology."}
            """);

        final Classification classification = classifier.classify(ARXIV_CS_RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        // the set's field and discipline win over the model's field
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Computer Science");
        assertThat(classification.newCollection().title()).isEqualTo("Information Retrieval");
        assertThat(classification.confidence()).isEqualTo(0.7);
        assertThat(classification.rationale()).contains("(set cs:cs:DL)");
        assertThat(classification.topology().kind()).as("a single PDF has no parts").isEqualTo(TopologyKind.NONE);
        assertThat(agents.chat.prompts()).as("no correction needed").hasSize(1);
        assertThat(agents.chat.prompts().get(0).getUserMessage().getText())
            .contains("discipline in its source: Computer Science");
    }

    @Test
    void placesARecordWhereRecordsOfItsSubjectAlreadyAre() {
        nestedRepository();
        final ClassificationRequest digitalLibraries = new ClassificationRequest("harvested/repo", "repo", "x",
            ARXIV_CS_RECORD.titles(), ARXIV_CS_RECORD.descriptions(), List.of("Digital Libraries", "H.3.7"),
            List.of(), List.of(), List.of(), List.of(), List.of("cs:cs:DL"), List.of("application/pdf"));
        agents.chat.answer("""
            {"field": "Natural Sciences", "confidence": 0.2}
            """);

        final Classification classification = classifier.classify(digitalLibraries, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.EXISTING_COLLECTION);
        assertThat(classification.collection().path())
            .isEqualTo("communities/natural-sciences/computer-science/digital-libraries");
    }

    @Test
    void takesNoExistingCollectionOutsideTheRecordsOwnDiscipline() {
        // Science > Chemistry (c1) is no place for a computer science paper the model put there
        agents.chat.answer("""
            {"collection": "c1", "confidence": 0.9}
            """, """
            {"collection": "c1", "confidence": 0.9}
            """);

        final Classification classification = classifier.classify(ARXIV_CS_RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.newCommunities()).extracting(Classification.NewStructure::title)
            .containsExactly("Natural Sciences", "Computer Science");

        // a record that brings no discipline of its own goes where the model says
        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).collection().path())
            .isEqualTo("communities/science/chemistry");
    }

    @Test
    void placesARecordByItsOwnClassificationWhenNoAnswerCanBeRead() {
        emptyRepository();
        agents.chat.answer("I think this is about computers.", "still prose");

        final Classification classification = classifier.classify(ARXIV_CS_RECORD, Platform.TOKEN, Locale.ENGLISH);

        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.newCollection().title()).isEqualTo("Information Retrieval");
        assertThat(classification.confidence()).isEqualTo(0.7);
    }

    @Test
    void readsNoSetSpecAsATopic() {
        emptyRepository();
        agents.chat.answer("""
            {"collection": "cs:cs:DL", "field": "Natural Sciences", "discipline": "Computer Science",
             "confidence": 0.6}
            """, """
            {"collection": "cs:cs:DL", "field": "Natural Sciences", "discipline": "Computer Science",
             "topic": "Latin American Studies", "confidence": 0.6}
            """);

        final Classification classification = classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH);

        // a general topic of several words is no record's title
        assertThat(classification.placement()).isEqualTo(Placement.NEW_COMMUNITY);
        assertThat(classification.newCollection().title()).isEqualTo("Latin American Studies");
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("topic (the new collection's title) is missing");
    }

    @Test
    void infersTheTopologyDecisionFromTheTopologyGiven() {
        agents.chat.answer("""
            {"collection": "c2", "topology": "thesis", "confidence": 0.9}
            """, """
            {"collection": "c2", "topology": "", "confidence": 0.9}
            """);

        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).topology())
            .isEqualTo(new Classification.TopologyChoice(TopologyKind.EXISTING, "thesis", "Thesis", null));
        assertThat(classifier.classify(RECORD, Platform.TOKEN, Locale.ENGLISH).topology().kind())
            .isEqualTo(TopologyKind.NONE);
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
