package com.articulorium.ai.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Optional;

import com.articulorium.agent.Agent;
import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.support.Agents;
import com.articulorium.ai.support.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests profile suggestions against a mocked platform and a scripted model, with profiles written
 * as the profile editor writes them.
 */
class ProfileEnhancerTest {

    private static final String P = "http://platform.test/profiles/curie";

    private static final String TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";

    private static final String LABEL = "<http://www.w3.org/2000/01/rdf-schema#label>";

    private static final String VIVO = "http://vivoweb.org/ontology/core#";

    private static final String OBO = "http://purl.obolibrary.org/obo/";

    /** A profile with a position, a degree, two publications, a research area and a keyword. */
    private static final String CURIE = """
        <> %1$s <http://xmlns.com/foaf/0.1/Person> .
        <> %1$s <%3$sFacultyMember> .
        <> %2$s "Curie, Marie" .
        <> <%3$soverview> "Physicist." .
        <> <%3$sfreetextKeyword> "polonium" .
        <> <%3$shasResearchArea> <%5$s#area-1> .
        <%5$s#area-1> %1$s <http://www.w3.org/2004/02/skos/core#Concept> .
        <%5$s#area-1> %2$s "Radioactivity" .
        <> <%3$srelatedBy> <%5$s#position-1> .
        <%5$s#position-1> %1$s <%3$sFacultyPosition> .
        <%5$s#position-1> %2$s "Professor of General Physics" .
        <%5$s#position-1> <%3$srelates> <> .
        <%5$s#position-1> <%3$srelates> <%5$s#organization-1> .
        <%5$s#organization-1> %2$s "University of Paris" .
        <> <%3$srelatedBy> <%5$s#authorship-1> .
        <%5$s#authorship-1> %1$s <%3$sAuthorship> .
        <%5$s#authorship-1> <%3$srelates> <> .
        <%5$s#authorship-1> <%3$srelates> <%5$s#publication-1> .
        <%5$s#publication-1> %2$s "Radioactive substances" .
        <%5$s#publication-1> <%3$shasPublicationVenue> <%5$s#venue-1> .
        <%5$s#venue-1> %2$s "Annales de chimie" .
        <> <%3$srelatedBy> <%5$s#authorship-2> .
        <%5$s#authorship-2> %1$s <%3$sAuthorship> .
        <%5$s#authorship-2> <%3$srelates> <%5$s#publication-2> .
        <%5$s#publication-2> %2$s "On a new radioactive substance" .
        <> <%4$sRO_0000056> <%5$s#education-1> .
        <%5$s#education-1> %1$s <%3$sEducationalProcess> .
        <%5$s#education-1> <%3$smajorField> "Physics" .
        <%5$s#education-1> <%4$sRO_0002234> <%5$s#degree-1> .
        <%5$s#degree-1> %2$s "Doctor of Science" .
        """.formatted(TYPE, LABEL, VIVO, OBO, P);

    private Agents agents;

    private ProfileEnhancer enhancer;

    @BeforeEach
    void setUp() {
        agents = new Agents();
        enhancer = new ProfileEnhancer(agents.model, agents.access, agents.reader, agents.properties);
    }

    @Test
    void suggestsWhatTheProfileLacksFromWhatItRecords() {
        agents.platform.access("profiles/curie", true, "read", "write").rdf("profiles/curie", CURIE);
        agents.chat.answer("""
            {"overview": "Marie Curie studies radioactivity.",
             "researchAreas": ["radioactivity", "Radiochemistry", "Radiochemistry", " ", "Nuclear physics"],
             "keywords": ["Polonium", "radium", "radiochemistry", "isolation of radium"]}
            """);

        final ProfileSuggestion suggestion = enhancer.suggest(new ProfileSuggestionRequest("/profiles/curie/"),
            Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.path()).isEqualTo("profiles/curie");
        assertThat(suggestion.overview()).isEqualTo("Marie Curie studies radioactivity.");
        assertThat(suggestion.hasOverview()).isTrue();
        // the profile's own research area is left out, and so are repeats
        assertThat(suggestion.researchAreas()).containsExactly("Radiochemistry", "Nuclear physics");
        // the profile's keyword and a suggested research area are left out
        assertThat(suggestion.keywords()).containsExactly("radium", "isolation of radium");
        assertThat(suggestion.basedOn()).isEqualTo(new ProfileSuggestion.BasedOn(1, 1, 2));
        assertThat(suggestion.language()).isEqualTo("en");

        final String user = agents.chat.prompts().get(0).getUserMessage().getText();
        assertThat(user).contains("Curie, Marie")
            .contains("Professor of General Physics, University of Paris")
            .contains("Doctor of Science in Physics")
            .contains("Radioactive substances (Annales de chimie)")
            .contains("On a new radioactive substance")
            .contains("Physicist.")
            .contains("Radioactivity; polonium");
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText()).contains("at most 8")
            .contains("at most 12").contains("English (en)");
    }

    @Test
    void keepsToTheConfiguredLimits() {
        agents.properties.getProfiles().setMaxResearchAreas(1);
        agents.properties.getProfiles().setMaxKeywords(0);
        agents.platform.access("profiles/curie", true, "write").rdf("profiles/curie", CURIE);
        agents.chat.answer("{\"overview\": \"\", \"researchAreas\": [\"A\", \"B\"], \"keywords\": [\"k\"]}");

        final ProfileSuggestion suggestion = enhancer.suggest(new ProfileSuggestionRequest("profiles/curie"),
            Platform.TOKEN, Locale.FRENCH);

        assertThat(suggestion.researchAreas()).containsExactly("A");
        assertThat(suggestion.keywords()).isEmpty();
        assertThat(suggestion.overview()).isEmpty();
        assertThat(agents.chat.prompts().get(0).getSystemMessage().getText()).contains("French (fr)");
    }

    @Test
    void asksNothingAboutAProfileThatRecordsNothing() {
        agents.platform.access("profiles/new", true, "write").rdf("profiles/new", """
            <> %s <http://xmlns.com/foaf/0.1/Person> .
            <> %s "Doe, Jane" .
            """.formatted(TYPE, LABEL));

        final ProfileSuggestion suggestion = enhancer.suggest(new ProfileSuggestionRequest("profiles/new"),
            Platform.TOKEN, Locale.ENGLISH);

        assertThat(suggestion.basedOn()).isEqualTo(new ProfileSuggestion.BasedOn(0, 0, 0));
        assertThat(suggestion.researchAreas()).isEmpty();
        assertThat(suggestion.hasOverview()).isFalse();
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void refusesResourcesThatAreNoProfile() {
        agents.platform.access("books/moby", true, "write").rdf("books/moby", """
            <> %s <http://pcdm.org/models#Object> .
            """.formatted(TYPE));

        assertThatThrownBy(() -> enhancer.suggest(new ProfileSuggestionRequest("books/moby"), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED));
    }

    @Test
    void refusesReadersAndMissingPaths() {
        agents.platform.access("profiles/curie", true, "read");

        assertThatThrownBy(() -> enhancer.suggest(new ProfileSuggestionRequest("profiles/curie"), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
        assertThatThrownBy(() -> enhancer.suggest(new ProfileSuggestionRequest(" "), Platform.TOKEN, Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_REQUEST));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void isAnAgentWhileTurnedOn() {
        assertThat(enhancer.agent()).map(Agent::getName).contains("ai-profile-enhancer");
        agents.properties.getProfiles().setEnabled(false);
        assertThat(enhancer.agent()).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> enhancer.suggest(new ProfileSuggestionRequest("profiles/curie"), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.CAPABILITY_DISABLED));
    }

}
