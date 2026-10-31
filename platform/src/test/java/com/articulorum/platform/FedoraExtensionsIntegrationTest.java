package com.articulorum.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.articulorum.domain.vocabulary.Memento;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the Fedora API extensions served next to the LDP resources
 * ({@code fcr:versions}, {@code fcr:fixity}, {@code fcr:search}) and of the OpenAPI description,
 * over the full application context (H2 database, file system OCFL storage root).
 */
@SpringBootTest
@AutoConfigureMockMvc
class FedoraExtensionsIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String TURTLE = "text/turtle";

    @Autowired
    private MockMvc mvc;

    private static String unique(final String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String create(final String parent, final String slug, final MediaType type, final String body)
            throws Exception {
        final String location = mvc.perform(post(parent).header("Slug", slug).contentType(type)
                .content(body.getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return location.substring(BASE.length());
    }

    private String container() throws Exception {
        return create("/", unique("ext"), MediaType.parseMediaType(TURTLE),
            "<> <http://purl.org/dc/terms/title> \"extensions\" .");
    }

    /** Every binary write is an OCFL version listed by fcr:versions, oldest first. */
    @Test
    void binaryWritesAreListedAsVersions() throws Exception {
        final String file = create(container(), "a.txt", MediaType.TEXT_PLAIN, "first");
        mvc.perform(put(file).contentType(MediaType.TEXT_PLAIN).content("second")).andExpect(status().is2xxSuccessful());

        mvc.perform(get(file + "/fcr:versions").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.original").value(BASE + file))
            .andExpect(jsonPath("$.timemap").value(BASE + file + "/fcr:versions"))
            .andExpect(jsonPath("$.versions", hasSize(greaterThanOrEqualTo(2))))
            .andExpect(jsonPath("$.versions[0].id").value("v1"))
            .andExpect(jsonPath("$.versions[0].hasContent").value(true))
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(containsString("rel=\"original timegate\""))));

        mvc.perform(get(file + "/fcr:versions/v1"))
            .andExpect(status().isOk())
            .andExpect(content().string("first"))
            .andExpect(header().exists(Memento.MEMENTO_DATETIME_HEADER));
        mvc.perform(get(file)).andExpect(content().string("second"));
    }

    /** POST fcr:versions snapshots an RDF source; the memento renders the old triples. */
    @Test
    void rdfSourcesAreVersionedOnRequest() throws Exception {
        final String parent = container();
        final String location = mvc.perform(post(parent + "/fcr:versions"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, containsString(parent + "/fcr:versions/v")))
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(put(parent).contentType(TURTLE).content("<> <http://purl.org/dc/terms/title> \"changed\" ."))
            .andExpect(status().is2xxSuccessful());

        mvc.perform(get(location.substring(BASE.length())).accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("extensions")));
        mvc.perform(get(parent + "/fcr:versions").accept(Memento.LINK_FORMAT))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(Memento.LINK_FORMAT))
            .andExpect(content().string(containsString("rel=\"memento\"")));
    }

    /** Unknown versions are 404; the root is not versioned. */
    @Test
    void missingVersionsAreNotFound() throws Exception {
        final String parent = container();

        mvc.perform(get(parent + "/fcr:versions/v99")).andExpect(status().isNotFound());
        mvc.perform(get(parent + "/fcr:versions/latest")).andExpect(status().isNotFound());
        mvc.perform(post("/fcr:versions")).andExpect(status().isMethodNotAllowed());
    }

    /** fcr:fixity re-hashes the stored bytes and validates the OCFL object. */
    @Test
    void fixityChecksStoredBytes() throws Exception {
        final String file = create(container(), "f.txt", MediaType.TEXT_PLAIN, "fixed bytes");

        mvc.perform(get(file + "/fcr:fixity"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.actualSize").value(11))
            .andExpect(jsonPath("$.storageValid").value(true));
        mvc.perform(get(container() + "/fcr:fixity")).andExpect(status().isMethodNotAllowed());
    }

    /** Extensions advertise and enforce their methods. */
    @Test
    void extensionsAdvertiseTheirMethods() throws Exception {
        final String parent = container();

        mvc.perform(options(parent + "/fcr:versions"))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
        mvc.perform(delete(parent + "/fcr:versions")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(parent + "/fcr:bogus")).andExpect(status().isNotFound());
    }

    /** fcr:search filters by path wildcard and media type, with paging. */
    @Test
    void searchFindsResources() throws Exception {
        final String parent = container();
        create(parent, "s.csv", MediaType.parseMediaType("text/csv"), "a,b\n1,2\n");
        create(parent, "s.txt", MediaType.TEXT_PLAIN, "text");

        mvc.perform(get("/fcr:search")
                .param("condition", "fedora_id=" + parent.substring(1) + "/*")
                .param("condition", "mime_type=text/csv")
                .param("max_results", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].fedora_id", endsWith("/s.csv")))
            .andExpect(jsonPath("$.pagination.totalResults").value(1));
        mvc.perform(get("/fcr:search").param("order", "sideways")).andExpect(status().isBadRequest());
        mvc.perform(get("/fcr:search").param("max_results", "many")).andExpect(status().isBadRequest());
        mvc.perform(get("/fcr:search").param("order_by", "colour")).andExpect(status().isBadRequest());
    }

    /** The OpenAPI description documents LDP and the extensions; Swagger UI is served. */
    @Test
    void publishesTheOpenApiDescription() throws Exception {
        final String json = mvc.perform(get("/.well-known/openapi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.info.title").value("Articulorum Linked Data Platform"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(json).contains("fcr:versions", "fcr:fixity", "fcr:search", "Linked Data Platform");
        mvc.perform(get("/.well-known/swagger-ui.html"))
            .andExpect(status().is3xxRedirection());
    }

}
