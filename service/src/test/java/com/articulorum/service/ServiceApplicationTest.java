package com.articulorum.service;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the service over HTTP (MockMvc against the full application context).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceApplicationTest {

    /** The first bytes of every PNG file. */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

    @Autowired
    private MockMvc mvc;

    @Test
    void rendersTheApplicationWithItsBaseHref() throws Exception {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/\"")));
    }

    @Test
    void servesTheApplicationUnderItsContextPathWithoutForwardedHeaders() throws Exception {
        mvc.perform(get("/ui/").contextPath("/ui").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/ui/\"")));
        mvc.perform(get("/ui/browse/books/notes.txt").contextPath("/ui").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/ui/\"")));
    }

    @Test
    void honoursTheGatewayPrefixInTheBaseHref() throws Exception {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML).header("X-Forwarded-Prefix", "/ui"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/ui/\"")));
    }

    @Test
    void clientRoutesAreAnsweredWithTheApplication() throws Exception {
        mvc.perform(get("/browse/books/moby-dick").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<ui-root>")));
    }

    @Test
    void deepLinksToResourcesNamedLikeFilesAreAnsweredWithTheApplication() throws Exception {
        mvc.perform(get("/browse/books/notes.txt").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<ui-root>")));
    }

    @Test
    void missingAssetsAreNotFound() throws Exception {
        mvc.perform(get("/missing.js"))
            .andExpect(status().isNotFound());
    }

    @Test
    void servesTheUiSettings() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.platformUrl").value("../platform/"))
            .andExpect(jsonPath("$.title").value("Articulorum"));
    }

    @Test
    void listsTheAgents() throws Exception {
        mvc.perform(get("/api/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("owl"))
            .andExpect(jsonPath("$[0].description").value("read owl"));
    }

    @Test
    void detectsMediaTypesFromContent() throws Exception {
        final MockMultipartFile file = new MockMultipartFile("file", "picture.bin", "application/octet-stream",
            PNG_SIGNATURE);
        mvc.perform(multipart("/api/media-type").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mediaType").value("image/png"))
            .andExpect(jsonPath("$.filename").value("picture.bin"))
            .andExpect(jsonPath("$.size").value(PNG_SIGNATURE.length));
    }

    @Test
    void detectsMediaTypesFromNames() throws Exception {
        final MockMultipartFile file = new MockMultipartFile("file", "notes.txt", null,
            "plain words".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/media-type").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mediaType").value("text/plain"));
    }

    @Test
    void rejectsEmptyFiles() throws Exception {
        mvc.perform(multipart("/api/media-type").file(new MockMultipartFile("file", "empty.txt", null, new byte[0])))
            .andExpect(status().isBadRequest());
    }

    @Test
    void publishesTheOpenApiDocument() throws Exception {
        mvc.perform(get("/api/docs"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/api/media-type")))
            .andExpect(content().string(containsString("/api/config")));
    }

}
