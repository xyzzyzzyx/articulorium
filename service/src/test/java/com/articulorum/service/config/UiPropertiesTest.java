package com.articulorum.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UiProperties}.
 */
class UiPropertiesTest {

    @Test
    void appliesDefaults() {
        final UiProperties properties = new UiProperties(null, " ");
        assertThat(properties.platformUrl()).isEqualTo(UiProperties.DEFAULT_PLATFORM_URL);
        assertThat(properties.title()).isEqualTo(UiProperties.DEFAULT_TITLE);
        assertThat(properties.apiDocsUrl()).isEqualTo("../swagger-ui.html");
        assertThat(properties.topologyUrl()).isEqualTo("../topology/");
        assertThat(properties.transformUrl()).isEqualTo("../transform/");
        assertThat(properties.communitiesPath()).isEqualTo("communities");
    }

    @Test
    void keepsEveryConfiguredUrl() {
        final UiProperties properties = new UiProperties("p/", "T", " /docs ", "t/", "x/", "orgs");
        assertThat(properties.apiDocsUrl()).isEqualTo("/docs");
        assertThat(properties.topologyUrl()).isEqualTo("t/");
        assertThat(properties.transformUrl()).isEqualTo("x/");
        assertThat(properties.communitiesPath()).isEqualTo("orgs");
    }

    @Test
    void keepsConfiguredValues() {
        final UiProperties properties = new UiProperties(" http://localhost:9001/ ", "Library");
        assertThat(properties.platformUrl()).isEqualTo("http://localhost:9001/");
        assertThat(properties.title()).isEqualTo("Library");
    }

}
