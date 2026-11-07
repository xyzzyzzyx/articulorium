package com.articulorium.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests the public addresses derived from the settings.
 */
class PublicUrlsTest {

    @Test
    void resolvesServicesNextToThePublicAddress() {
        final PublicUrls urls = new PublicUrls(new DiscoveryProperties());

        assertThat(urls.publicUrl()).isEqualTo("http://localhost:8000/discover/");
        assertThat(urls.platformBase()).isEqualTo("http://localhost:8000/platform/");
        assertThat(urls.iiifUrl("communities/c/book")).isEqualTo("http://localhost:8000/transform/iiif/communities/c/book");
        assertThat(urls.repositoryUiUrl("a/b")).isEqualTo("http://localhost:8000/ui/browse/a/b");
        assertThat(urls.absolute("/branding/logo.svg")).isEqualTo("http://localhost:8000/branding/logo.svg");
        assertThat(urls.absolute("https://cdn.example.org/x.css")).isEqualTo("https://cdn.example.org/x.css");
        assertThat(urls.absolute(" ")).isNull();
    }

    @Test
    void mapsPathsAndIris() {
        final DiscoveryProperties properties = new DiscoveryProperties();
        properties.setPublicUrl("https://collections.example.org/discover/");
        properties.setPlatformPublicUrl("https://repository.example.org/ldp/");
        final PublicUrls urls = new PublicUrls(properties);

        assertThat(urls.iriOf("communities/c")).isEqualTo("https://repository.example.org/ldp/communities/c");
        assertThat(urls.pathOf("https://repository.example.org/ldp/communities/c/")).isEqualTo("communities/c");
        assertThat(urls.pathOf("https://elsewhere.example.org/ldp/x")).isNull();
        assertThat(urls.pathOf(null)).isNull();
    }

    @Test
    void recognizesIrisMintedUnderOtherPlatformAddresses() {
        final DiscoveryProperties properties = new DiscoveryProperties();
        properties.getPlatformIriBases().add("https://ldp.internal.example.org/root");
        final PublicUrls urls = new PublicUrls(properties);

        assertThat(urls.pathOf("http://articulorium-platform/communities/c/atlas")).isEqualTo("communities/c/atlas");
        assertThat(urls.pathOf("https://ldp.internal.example.org/root/x")).isEqualTo("x");
        assertThat(urls.irisOf("a/b")).containsExactly("http://localhost:8000/platform/a/b",
            "http://articulorium-platform/a/b", "https://ldp.internal.example.org/root/a/b");
        assertThat(urls.iriOf("a/b")).isEqualTo("http://localhost:8000/platform/a/b");
    }

}
