package com.articulorium.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.articulorium.service.config.UiProperties;

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
        assertThat(properties.harvestUrl()).isEqualTo("../harvest/");
        assertThat(properties.aiUrl()).isEqualTo("../ai/");
        assertThat(properties.transformUrl()).isEqualTo("../transform/");
        assertThat(properties.communitiesPath()).isEqualTo("communities");
        assertThat(properties.profilesPath()).isEqualTo("profiles");
    }

    @Test
    void keepsAConfiguredProfileContainer() {
        final UiProperties properties =
            new UiProperties(null, null, null, null, null, null, " people ", null, null, null);
        assertThat(properties.profilesPath()).isEqualTo("people");
    }

    @Test
    void keepsAConfiguredHarvestService() {
        final UiProperties properties =
            new UiProperties(null, null, null, null, null, null, null, null, " https://harvest.example.org/ ", null);
        assertThat(properties.harvestUrl()).isEqualTo("https://harvest.example.org/");
    }

    @Test
    void keepsAConfiguredAiAgent() {
        final UiProperties properties =
            new UiProperties(null, null, null, null, null, null, null, null, null, " https://ai.example.org/ ");
        assertThat(properties.aiUrl()).isEqualTo("https://ai.example.org/");
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

    @Test
    void signsInWithOpenIdConnectByDefault() {
        final UiProperties properties = new UiProperties(null, null);
        assertThat(properties.auth().provider()).isEqualTo(UiProperties.Auth.OIDC);
        assertThat(properties.auth().oidc().authority()).isEqualTo("../auth/realms/articulorium");
        assertThat(properties.auth().oidc().clientId()).isEqualTo("articulorium-ui");
        assertThat(properties.auth().oidc().scope()).isEqualTo("openid profile");
    }

    @Test
    void keepsConfiguredSignIn() {
        final UiProperties.Auth auth = new UiProperties.Auth(" Token ",
            new UiProperties.Oidc("https://idp.example.org/realms/x", "library", "openid"));
        assertThat(auth.provider()).isEqualTo("token");
        assertThat(auth.oidc().authority()).isEqualTo("https://idp.example.org/realms/x");
        assertThat(auth.oidc().clientId()).isEqualTo("library");
        assertThat(auth.oidc().scope()).isEqualTo("openid");
        assertThat(new UiProperties.Auth("none", null).provider()).isEqualTo("none");
    }

    @Test
    void rejectsUnknownProviders() {
        assertThatThrownBy(() -> new UiProperties.Auth("saml", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oidc, token or none");
    }

}
