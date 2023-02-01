package com.articulorium.ai.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Locale;

import com.articulorium.ai.AgentException;
import com.articulorium.ai.AgentException.Reason;
import com.articulorium.ai.support.Agents;
import com.articulorium.ai.support.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Tests topology suggestions against a mocked platform and topology service and a scripted model.
 */
class TopologyAssistantTest {

    private static final String SUMMARIES = """
        [{"id": "book", "name": "Book", "description": "A monograph with pages", "builtIn": true,
          "rootLabel": "Book", "depth": 2},
         {"id": "scrapbook", "name": "Scrapbook", "description": "Custom", "builtIn": false,
          "rootLabel": "Scrapbook", "depth": 1}]
        """;

    private static final String BOOK = """
        {"id": "book", "name": "Book", "builtIn": true, "root": {"key": "book", "label": "Book", "pcdmType": "Object",
         "types": ["http://purl.org/ontology/bibo/Book"], "files": [{"use": "http://pcdm.org/use#OriginalFile",
         "label": "PDF", "mediaTypes": ["application/pdf"]}],
         "children": [{"min": 0, "max": -1, "initial": 1, "slugPattern": "page-{n}",
           "node": {"key": "page", "label": "Page", "pcdmType": "Object"}}]}}
        """;

    private static final String DRAFT = """
        {"decision": "NEW", "confidence": 0.7, "rationale": "No topology has loose clippings.",
         "draft": {"id": "Herbarium Sheets", "name": "Herbarium", "description": "Pressed plants",
           "root": {"key": "sheet", "label": "Sheet", "pcdmType": "Object",
             "metadata": [{"predicate": "http://purl.org/dc/terms/title", "label": "Title", "required": true}],
             "files": [{"use": "http://pcdm.org/use#OriginalFile", "label": "Scan", "mediaTypes": ["image/tiff"]}]}}}
        """;

    private Agents agents;

    private TopologyAssistant assistant;

    @BeforeEach
    void setUp() {
        agents = new Agents();
        assistant = new TopologyAssistant(agents.model, agents.access, agents.reader, agents.topologies,
            agents.properties);
        agents.platform.topologies(SUMMARIES).topology("book", BOOK)
            .topology("scrapbook", "{\"id\": \"scrapbook\", \"name\": \"Scrapbook\", \"root\": {\"key\": \"sb\","
                + " \"label\": \"Scrapbook\", \"pcdmType\": \"Object\"}}");
    }

    private void validation(final String body) {
        agents.platform.server().expect(times(1), requestTo(Platform.TOPOLOGY + "topologies/validation"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", Platform.TOKEN))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void recommendsAnExistingTopology() {
        agents.platform.access("topologies", true, "read", "write");
        agents.chat.answer("""
            {"decision": "EXISTING", "existingId": "book", "confidence": 0.92, "rationale": "Bound volumes of pages."}
            """);

        final TopologySuggestion suggestion = assistant.suggest(
            new TopologySuggestionRequest("Printed volumes with numbered pages", null, null), Platform.TOKEN,
            Locale.ENGLISH);

        assertThat(suggestion.recommendation()).isEqualTo(TopologySuggestion.Recommendation.EXISTING);
        assertThat(suggestion.existing().id()).isEqualTo("book");
        assertThat(suggestion.draft()).isNull();
        assertThat(suggestion.confidence()).isEqualTo(0.92);
        final String user = agents.chat.prompts().get(0).getUserMessage().getText();
        assertThat(user).contains("- id book: Book").contains("Page [Object]").contains("files PDF (application/pdf)")
            .contains("<<<BEGIN material-description>>>\nPrinted volumes with numbered pages");
    }

    @Test
    void draftsANewTopologyCheckedByTheTopologyApi() {
        agents.platform.access("communities/natural-history", true, "write");
        validation("{\"valid\": true, \"errors\": [], \"exists\": false}");
        agents.chat.answer(DRAFT);

        final TopologySuggestion suggestion = assistant.suggest(
            new TopologySuggestionRequest("Herbarium sheets", null, "communities/natural-history"), Platform.TOKEN,
            Locale.ENGLISH);

        assertThat(suggestion.recommendation()).isEqualTo(TopologySuggestion.Recommendation.NEW);
        assertThat(suggestion.draft().id()).isEqualTo("herbarium-sheets");
        assertThat(suggestion.draft().builtIn()).isFalse();
        assertThat(suggestion.draft().root().files()).extracting(TopologyDraft.FileKind::label).containsExactly("Scan");
        assertThat(suggestion.errors()).isEmpty();
        assertThat(agents.chat.prompts()).hasSize(1);
        agents.platform.server().verify();
    }

    @Test
    void asksTheModelOnceToCorrectAnInvalidDraft() {
        agents.platform.access("topologies", true, "write");
        validation("{\"valid\": false, \"errors\": [\"node sheet: pcdmType must be Collection or Object\"]}");
        validation("{\"valid\": false, \"errors\": [\"still wrong\"]}");
        agents.chat.answer(DRAFT, """
            {"id": "herbarium", "name": "Herbarium", "root": {"key": "sheet", "label": "Sheet", "pcdmType": "Object"}}
            """);

        final TopologySuggestion suggestion = assistant.suggest(new TopologySuggestionRequest("Herbarium", null, null),
            Platform.TOKEN, Locale.ENGLISH);

        assertThat(agents.chat.prompts()).hasSize(2);
        assertThat(agents.chat.prompts().get(1).getUserMessage().getText())
            .contains("<<<BEGIN problems>>>\nnode sheet: pcdmType must be Collection or Object");
        assertThat(suggestion.draft().id()).isEqualTo("herbarium");
        // what is still wrong goes back with the draft, to fix in the editor
        assertThat(suggestion.errors()).containsExactly("still wrong");
    }

    @Test
    void neverReusesTheIdOfAnExistingTopology() {
        agents.platform.access("topologies", true, "write");
        validation("{\"valid\": true, \"errors\": [], \"exists\": false}");
        agents.chat.answer("""
            {"decision": "NEW", "draft": {"id": "book", "name": "Book", "root": {"key": "b", "label": "B",
             "pcdmType": "Object"}}}
            """);

        assertThat(assistant.suggest(new TopologySuggestionRequest("Books", null, null), Platform.TOKEN,
            Locale.ENGLISH).draft().id()).isEqualTo("book-2");
    }

    @Test
    void showsTheModelHowASampleContainerIsOrganised() {
        agents.platform.access("topologies", true, "write")
            .rdf("uploads/album", """
                <> <http://purl.org/dc/terms/title> "Family album" .
                <> <http://www.w3.org/ns/ldp#contains> <http://platform.test/uploads/album/page-1> .
                <> <http://www.w3.org/ns/ldp#contains> <http://platform.test/uploads/album/cover.tif> .
                """)
            .rdf("uploads/album/page-1", """
                <> <http://www.w3.org/ns/ldp#contains> <http://platform.test/uploads/album/page-1/photo.jpg> .
                """)
            .binary("uploads/album/page-1/photo.jpg", "image/jpeg", new byte[0], """
                <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
                <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "image/jpeg" .
                """)
            .binary("uploads/album/cover.tif", "image/tiff", new byte[0], """
                <> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
                <> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "image/tiff" .
                """);
        agents.chat.answer("{\"decision\": \"EXISTING\", \"existingId\": \"scrapbook\"}");

        assistant.suggest(new TopologySuggestionRequest("", "uploads/album", null), Platform.TOKEN, Locale.ENGLISH);

        assertThat(agents.chat.prompts().get(0).getUserMessage().getText()).contains("""
            <<<BEGIN sample-container>>>
            album "Family album"
              cover.tif (file image/tiff)
              page-1 (container, 1 children)
                photo.jpg (file image/jpeg)
            <<<END sample-container>>>""");
    }

    @Test
    void refusesAnswersNamingNoTopologyItWasShown() {
        agents.platform.access("topologies", true, "write");
        agents.chat.answer("{\"decision\": \"EXISTING\", \"existingId\": \"atlas\"}");

        assertThatThrownBy(() -> assistant.suggest(new TopologySuggestionRequest("Maps", null, null), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_OUTPUT));
    }

    @Test
    void needsWriteAccessToTheTopologiesOrTheContext() {
        agents.platform.access("topologies", true, "read");

        assertThatThrownBy(() -> assistant.suggest(new TopologySuggestionRequest("Maps", null, null), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.FORBIDDEN));
        assertThat(agents.chat.prompts()).isEmpty();
    }

    @Test
    void needsADescriptionOrASample() {
        assertThatThrownBy(() -> assistant.suggest(new TopologySuggestionRequest(" ", null, null), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_REQUEST));
        agents.properties.getTopology().setMaxDescriptionLength(5);
        assertThatThrownBy(() -> assistant.suggest(new TopologySuggestionRequest("too long", null, null), Platform.TOKEN,
            Locale.ENGLISH))
            .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_REQUEST));
    }

    @Test
    void derivesSlugs() {
        assertThat(TopologyAssistant.slugOf("Cartes postales (époque)")).isEqualTo("cartes-postales-epoque");
        assertThat(TopologyAssistant.slugOf("  ")).isEmpty();
        assertThat(TopologyAssistant.slugOf(null)).isEmpty();
    }

}
