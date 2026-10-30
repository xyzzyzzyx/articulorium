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
    }

    @Test
    void keepsConfiguredValues() {
        final UiProperties properties = new UiProperties(" http://localhost:9001/ ", "Library");
        assertThat(properties.platformUrl()).isEqualTo("http://localhost:9001/");
        assertThat(properties.title()).isEqualTo("Library");
    }

}
