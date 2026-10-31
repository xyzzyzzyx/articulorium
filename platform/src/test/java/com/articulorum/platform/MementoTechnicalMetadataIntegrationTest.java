package com.articulorum.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mementos of binaries carry the technical metadata (size, digest, media type, file name) the
 * binary had at that version, not the current values.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MementoTechnicalMetadataIntegrationTest {

    private static final String BASE = "http://localhost";

    @Autowired
    private MockMvc mvc;

    @Test
    void binaryMementosDescribeTheirOwnBytes() throws Exception {
        final String container = mvc.perform(post("/").header("Slug", "tm-" + UUID.randomUUID().toString().substring(0, 8))
                .contentType("text/turtle").content(""))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());
        final String file = mvc.perform(post(container).contentType("text/plain")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"first.txt\"")
                .content("abc".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());
        mvc.perform(put(file).contentType("text/csv")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"second.csv\"")
                .content("a,b,c,d,e".getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().is2xxSuccessful());

        // the RDF view of version 1 states the old size, type and name
        mvc.perform(get(file + "/fcr:versions/v1").accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"3\"^^<http://www.w3.org/2001/XMLSchema#long>")))
            .andExpect(content().string(containsString("\"text/plain\"")))
            .andExpect(content().string(containsString("\"first.txt\"")))
            .andExpect(content().string(containsString("urn:sha-256:")))
            .andExpect(content().string(not(containsString("second.csv"))));
        // and the bytes of version 1 are served with the media type they had
        mvc.perform(get(file + "/fcr:versions/v1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(content().string("abc"));
        mvc.perform(get(file + "/fcr:versions/v2").accept("application/n-triples"))
            .andExpect(content().string(containsString("\"second.csv\"")));
    }

}
