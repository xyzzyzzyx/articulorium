package com.articulorum.transform.iiif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests {@link IiifController}.
 */
class IiifControllerTest {

    private final IiifPresentationService presentation = mock(IiifPresentationService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new IiifController(presentation, new IiifProperties()))
        .build();

    @Test
    void servesManifestsWithCors() throws Exception {
        when(presentation.export(eq("c/book"), any())).thenReturn(Map.of("type", "Manifest"));

        mvc.perform(get("/iiif/c/book"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "*"))
            .andExpect(content().contentTypeCompatibleWith("application/ld+json"))
            .andExpect(jsonPath("$.type").value("Manifest"));
    }

    @Test
    void failuresKeepTheirStatus() throws Exception {
        when(presentation.export(eq("missing"), any())).thenThrow(new IiifException(404, "No resource", null));

        mvc.perform(get("/iiif/missing")).andExpect(status().isNotFound());
    }

    @Test
    void publicBasesFollowTheForwardedRequest() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/iiif/a");
        request.setScheme("https");
        request.setServerName("repo.example");
        request.setServerPort(443);
        request.setContextPath("/transform");
        request.setRequestURI("/transform/iiif/a/b");
        request.addHeader("Authorization", "Bearer x");

        final PublicContext context = IiifController.context(request, new IiifProperties());

        assertThat(context.serviceBase()).isEqualTo("https://repo.example/transform/");
        assertThat(context.platformBase()).isEqualTo("https://repo.example/platform/");
        assertThat(context.uiBase()).isEqualTo("https://repo.example/ui/");
        assertThat(context.authorization()).isEqualTo("Bearer x");
        assertThat(IiifController.pathOf(request)).isEqualTo("a/b");
        assertThat(context.pathOf("https://repo.example/platform/a/b/")).isEqualTo("a/b");
        assertThat(context.pathOf("https://elsewhere/x")).isNull();
    }

}
