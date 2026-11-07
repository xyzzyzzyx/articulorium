package com.articulorium.ai.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.support.Agents;
import com.articulorium.ai.support.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Tests topic models against a mocked platform and a scripted model.
 */
class TopicModelerTest {

    private static final String TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";

    private static final String CONTAINS = "<http://www.w3.org/ns/ldp#contains>";

    private static final String TITLE = "<http://purl.org/dc/terms/title>";

    private Agents agents;

    private TopicModeler modeler;

    @BeforeEach
    void setUp() {
        agents = new Agents();
        modeler = new TopicModeler(agents.model, agents.access, agents.reader, agents.properties);
        agents.platform.access("communities/science", true, "read", "write")
            .rdf("communities/science", """
                <> %s <https://articulorium.org/ns/repository#Community> .
                <> %s "Science" .
                <> %s <http://platform.test/communities/science/physics> .
                <> %s <http://platform.test/communities/science/notes> .
                """.formatted(TYPE, TITLE, CONTAINS, CONTAINS))
            .rdf("communities/science/physics", """
                <> %s <https://articulorium.org/ns/repository#Collection> .
                <> %s <http://pcdm.org/models#Collection> .
                <> %s "Physics" .
                <> %s <http://platform.test/communities/science/physics/optics> .
                <> %s <http://platform.test/communities/science/physics/secret> .
                <> %s <http://platform.test/communities/science/physics/gravity> .
                """.formatted(TYPE, TYPE, TITLE, CONTAINS, CONTAINS, CONTAINS))
            .rdf("communities/science/notes", """
                <> %s <http://pcdm.org/models#Object> .
                <> %s "Lab notes on lenses" .
                <> <http://purl.org/dc/terms/subject> "optics" .
                """.formatted(TYPE, TITLE))
            .rdf("communities/science/physics/optics", """
                <> %s <http://pcdm.org/models#Object> .
                <> %s "On the refraction of light" .
                <> <http://purl.org/dc/terms/creator> "A. Fresnel" .
                <> <http://purl.org/dc/terms/date> "1821" .
                """.formatted(TYPE, TITLE))
            .rdf("communities/science/physics/gravity", """
                <> %s <http://pcdm.org/models#Object> .
                <> %s "Orbits of the moons of Jupiter" .
                """.formatted(TYPE, TITLE))
            // an item the caller may not read
            .refused("communities/science/physics/secret", HttpStatus.FORBIDDEN);
    }

    @Test
    void modelsTheTopicsOfACommunityFromWhatTheCallerMayRead() {
        agents.chat.answer("""
            {"summary": "Physics papers and notes.", "topics": [
              {"label": "Optics", "keywords": ["light", "lenses", "light"], "description": "Light and lenses.",
               "items": ["d1", "d3", "d9"]},
              {"label": "optics", "items": ["d1"]},
              {"label": "Celestial mechanics", "items": ["d2"]}]}
            """);

        final TopicModel model = modeler.model(new TopicModelRequest("communities/science", null), Platform.TOKEN,
            Locale.ENGLISH);

        assertThat(model.analysed()).isEqualTo(3);
        assertThat(model.truncated()).isFalse();
        assertThat(model.summary()).isEqualTo("Physics papers and notes.");
        assertThat(model.language()).isEqualTo("en");
        assertThat(model.topics()).extracting(TopicModel.Topic::label).containsExactly("Optics", "Celestial mechanics");
        final TopicModel.Topic optics = model.topics().get(0);
        assertThat(optics.keywords()).containsExactly("light", "lenses");
        // d9 was never shown: it is dropped
        assertThat(optics.members()).extracting(TopicModel.Member::path)
            .containsExactly("communities/science/notes", "communities/science/physics/optics");
        assertThat(optics.share()).isEqualTo(2.0 / 3);
        final String user = agents.chat.prompts().get(0).getUserMessage().getText();
        assertThat(user).contains("d1: Lab notes on lenses. Subjects: optics")
            .contains("d2: Orbits of the moons of Jupiter")
            .contains("d3: On the refraction of light. By: A. Fresnel. Date: 1821")
            .doesNotContain("secret");
    }

    @Test
    void readsAtMostTheConfiguredNumberOfItems() {
        agents.properties.getTopics().setMaxDocuments(2);
        agents.chat.answer("{\"summary\": \"s\", \"topics\": []}");

        final TopicModel model = modeler.model(new TopicModelRequest("communities/science", 3), Platform.TOKEN,
            Locale.ENGLISH);

        assertThat(model.analysed()).isEqualTo(2);
        assertThat(model.truncated()).isTrue();
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText()).contains("at most 3");
    }

    @Test
    void asksNothingAboutAnEmptyCollection() {
        agents.platform.access("communities/empty", true, "write").rdf("communities/empty", """
            <> %s <https://articulorium.org/ns/repository#Collection> .
            """.formatted(TYPE));

        final TopicModel model = modeler.model(new TopicModelRequest("communities/empty", null), Platform.TOKEN,
            Locale.GERMAN);

        assertThat(model.topics()).isEmpty();
        assertThat(model.analysed()).isZero();
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesResourcesThatAreNoCommunityOrCollection() {
        agents.platform.access("communities/science/notes", true, "write");

        assertThatThrownBy(() -> modeler.model(new TopicModelRequest("communities/science/notes", null), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void refusesReaders() {
        agents.platform.access("communities/science/physics", true, "read");

        assertThatThrownBy(() -> modeler.model(new TopicModelRequest("communities/science/physics", null),
            Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
        assertThat(agents.chat.prompts()).isEmpty();
    }

}
