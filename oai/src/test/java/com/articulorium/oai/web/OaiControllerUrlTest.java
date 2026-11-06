package com.articulorium.oai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.oai.config.OaiProperties;
import com.articulorium.oai.service.HarvestContext;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests how {@link OaiController} derives the public URLs of a harvest.
 */
class OaiControllerUrlTest {

    @Test
    void resolvesRelativeLocationsAgainstTheServiceBase() {
        assertThat(OaiController.resolve("http://localhost:8000/oai/", "../platform/"))
            .isEqualTo("http://localhost:8000/platform/");
        assertThat(OaiController.resolve("http://localhost:9007/", "../platform/"))
            .isEqualTo("http://localhost:9007/platform/");
        assertThat(OaiController.resolve("http://localhost:9007/", "https://repo.example.org/ldp"))
            .isEqualTo("https://repo.example.org/ldp/");
    }

    @Test
    void forwardsTheHarvestersCredentials() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServerPort(9007);
        request.addHeader("Authorization", "Bearer secret");

        final HarvestContext context = OaiController.context(request, new OaiProperties());

        assertThat(context.baseUrl()).isEqualTo("http://localhost:9007");
        assertThat(context.platform().base()).isEqualTo("http://localhost:9007/platform/");
        assertThat(context.platform().authorization()).isEqualTo("Bearer secret");
        assertThat(context.landingPage("a/b")).isEqualTo("http://localhost:9007/ui/browse/a/b");
    }

}
