package com.articulorum.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Fedora;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of archival groups ({@code fedora:ArchivalGroup}): containers whose subtree is
 * stored in one OCFL object and versioned as a unit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArchivalGroupIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String TURTLE = "text/turtle";

    private static final String AG_LINK = "<" + Fedora.ARCHIVAL_GROUP + ">; rel=\"type\"";

    @Autowired
    private MockMvc mvc;

    private static String unique(final String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String create(final String parent, final String slug, final String contentType, final String body,
            final String... links) throws Exception {
        var request = post(parent).header("Slug", slug).contentType(contentType)
            .content(body.getBytes(StandardCharsets.UTF_8));
        for (final String link : links) {
            request = request.header(HttpHeaders.LINK, link);
        }
        final String location = mvc.perform(request)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return location.substring(BASE.length());
    }

    private String group() throws Exception {
        return create("/", unique("ag"), TURTLE, "<> <http://purl.org/dc/terms/title> \"A book\" .", AG_LINK);
    }

    /** An archival group is typed fedora:ArchivalGroup in headers and body and starts with v1. */
    @Test
    void archivalGroupIsAdvertisedAndVersionedOnCreation() throws Exception {
        final String group = group();

        mvc.perform(get(group).accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(containsString(Fedora.ARCHIVAL_GROUP))))
            .andExpect(content().string(containsString("ArchivalGroup")));
        mvc.perform(get(group + "/fcr:versions").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versions", hasSize(1)));
    }

    /** Members point at their group and are versioned through it, never on their own. */
    @Test
    void membersShareTheGroupHistory() throws Exception {
        final String group = group();
        final String page = create(group, "page-1", TURTLE, "<> <http://purl.org/dc/terms/title> \"Page 1\" .");
        final String image = create(page, "image.txt", "text/plain", "pixels");

        mvc.perform(get(image))
            .andExpect(status().isOk())
            .andExpect(content().string("pixels"))
            .andExpect(header().stringValues(HttpHeaders.LINK,
                hasItem(containsString(BASE + group + ">; rel=\"" + Articulorum.ARCHIVAL_GROUP_REL))));
        mvc.perform(post(page + "/fcr:versions")).andExpect(status().isMethodNotAllowed());

        // the binary write added a version of the group
        mvc.perform(get(group + "/fcr:versions").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.versions", hasSize(2)));
        mvc.perform(get(image + "/fcr:versions").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.versions", hasSize(1)))
            .andExpect(jsonPath("$.versions[0].hasContent").value(true));

        // versioning the group snapshots every member
        mvc.perform(post(group + "/fcr:versions")).andExpect(status().isCreated());
        mvc.perform(put(page).contentType(TURTLE).content("<> <http://purl.org/dc/terms/title> \"Changed\" ."))
            .andExpect(status().is2xxSuccessful());
        mvc.perform(get(page + "/fcr:versions/v3").accept(TURTLE))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Page 1")));
        mvc.perform(get(image + "/fcr:versions/v3")).andExpect(content().string("pixels"));
        mvc.perform(get(image + "/fcr:fixity"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    /** Deleting a member removes its files from the group's object; the group survives. */
    @Test
    void deletingAMemberWritesANewGroupVersion() throws Exception {
        final String group = group();
        final String file = create(group, "notes.txt", "text/plain", "notes");
        mvc.perform(delete(file)).andExpect(status().isNoContent());

        mvc.perform(get(file)).andExpect(status().isNotFound());
        mvc.perform(get(group + "/fcr:versions").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.versions", hasSize(3)));
        mvc.perform(delete(group)).andExpect(status().isNoContent());
        mvc.perform(get(group)).andExpect(status().isNotFound());
    }

    /** Archival groups cannot be nested, and only containers can be archival groups. */
    @Test
    void constraintsAreEnforced() throws Exception {
        final String group = group();
        mvc.perform(post(group).header("Slug", "inner").header(HttpHeaders.LINK, AG_LINK).contentType(TURTLE)
                .content(""))
            .andExpect(status().isConflict());
        mvc.perform(post("/").header("Slug", unique("ag-file")).header(HttpHeaders.LINK, AG_LINK)
                .contentType("text/plain").content("bytes"))
            .andExpect(status().isConflict());
        mvc.perform(put(group).header(HttpHeaders.LINK, AG_LINK).contentType(TURTLE).content(""))
            .andExpect(status().is2xxSuccessful());
        final String plain = create("/", unique("plain"), TURTLE, "");
        mvc.perform(put(plain).header(HttpHeaders.LINK, AG_LINK).contentType(TURTLE).content(""))
            .andExpect(status().isConflict());
    }

}
