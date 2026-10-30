package com.articulorum.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorum.platform.rdf.RdfMediaTypes;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Unit tests for {@link AcceptPreferences}.
 */
class AcceptPreferencesTest {

    @Test
    void browsersPreferHtml() {
        assertThat(AcceptPreferences.prefersHtml(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8")).isTrue();
    }

    @Test
    void rdfClientsAndWildcardsGetRdf() {
        assertThat(AcceptPreferences.prefersHtml("text/turtle")).isFalse();
        assertThat(AcceptPreferences.prefersHtml("*/*")).isFalse();
        assertThat(AcceptPreferences.prefersHtml("text/turtle, text/html;q=0.5")).isFalse();
        assertThat(AcceptPreferences.prefersHtml(null)).isFalse();
        assertThat(AcceptPreferences.prefersHtml("")).isFalse();
    }

    @Test
    void qualityValuesDecide() {
        assertThat(AcceptPreferences.prefersHtml("text/turtle;q=0.4, text/html")).isTrue();
        assertThat(AcceptPreferences.prefersHtml("text/html;q=0, text/turtle")).isFalse();
    }

    @Test
    void malformedHeadersFallBackToRdf() {
        assertThat(AcceptPreferences.prefersHtml("this is not a media type")).isFalse();
    }

    @Test
    void linkedDataClientsPreferTheDescriptionOfBinaries() {
        assertThat(AcceptPreferences.prefersDescription("text/turtle, application/rdf+xml;q=0.8", MediaType.APPLICATION_PDF))
            .isTrue();
    }

    @Test
    void clientsAcceptingTheBinaryGetTheBinary() {
        assertThat(AcceptPreferences.prefersDescription("*/*", MediaType.APPLICATION_PDF)).isFalse();
        assertThat(AcceptPreferences.prefersDescription("application/pdf", MediaType.APPLICATION_PDF)).isFalse();
        assertThat(AcceptPreferences.prefersDescription("text/turtle, */*;q=0.1", MediaType.APPLICATION_PDF)).isFalse();
        assertThat(AcceptPreferences.prefersDescription("text/*", RdfMediaTypes.TEXT_TURTLE)).isFalse();
        assertThat(AcceptPreferences.prefersDescription(null, MediaType.APPLICATION_PDF)).isFalse();
        assertThat(AcceptPreferences.prefersDescription("image/png", MediaType.APPLICATION_PDF)).isFalse();
    }

}
