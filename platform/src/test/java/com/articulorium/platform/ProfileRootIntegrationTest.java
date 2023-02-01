package com.articulorium.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.articulorium.domain.vocabulary.Articulorium;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The container of the academic profiles is created on start-up, and a VIVO profile with hash
 * IRIs for its contact card, positions and other parts round-trips as one resource.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileRootIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String PREFIXES = """
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix vcard: <http://www.w3.org/2006/vcard/ns#> .
        @prefix vivo: <http://vivoweb.org/ontology/core#> .
        @prefix obo: <http://purl.obolibrary.org/obo/> .
        <#vcard> a vcard:Individual ; vcard:hasName <#vcard-name> .
        <#vcard-name> a vcard:Name ; vcard:givenName "Marie" ; vcard:familyName "Curie" .
        """;

    private static final String PROFILE = PREFIXES + """
        <> a foaf:Person, vivo:FacultyMember ; rdfs:label "Curie, Marie" ;
           obo:ARG_2000028 <#vcard> ; vivo:relatedBy <#position-1> .
        <#position-1> a vivo:FacultyPosition ; rdfs:label "Professor" ; vivo:relates <>, <#org-1> .
        <#org-1> a foaf:Organization ; rdfs:label "University of Paris" .
        """;

    private static final String PROFILE_WITHOUT_POSITION = PREFIXES + """
        <> a foaf:Person, vivo:FacultyMember ; rdfs:label "Curie, Marie" ; obo:ARG_2000028 <#vcard> .
        """;

    @Autowired
    private MockMvc mvc;

    @Test
    void profileContainerExists() throws Exception {
        mvc.perform(get("/profiles").accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(Articulorium.PROFILE_ROOT)))
            .andExpect(content().string(containsString("\"Profiles\"")));
        mvc.perform(get("/").accept("application/n-triples"))
            .andExpect(content().string(containsString("/profiles>")));
    }

    @Test
    void storesAProfileWithItsParts() throws Exception {
        final MvcResult created = mvc.perform(post("/profiles")
                .header("Slug", "marie-curie")
                .header("Link", "<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"")
                .contentType("text/turtle")
                .content(PROFILE))
            .andExpect(status().isCreated())
            .andReturn();
        final String iri = created.getResponse().getHeader("Location");
        final String path = iri.substring(BASE.length());

        final MvcResult read = mvc.perform(get(path).accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "<" + iri + "#position-1> <http://vivoweb.org/ontology/core#relates> <" + iri + "> .")))
            .andExpect(content().string(containsString(
                "<" + iri + "#vcard-name> <http://www.w3.org/2006/vcard/ns#familyName> \"Curie\" .")))
            .andReturn();
        final String etag = read.getResponse().getHeader("ETag");

        // replacing the profile drops the parts the new body leaves out
        mvc.perform(put(path).header("If-Match", etag).contentType("text/turtle").content(PROFILE_WITHOUT_POSITION))
            .andExpect(status().isNoContent());
        mvc.perform(get(path).accept("application/n-triples"))
            .andExpect(content().string(not(containsString("#position-1>"))))
            .andExpect(content().string(containsString("#vcard-name>")));

        // a replacement based on the old state is refused
        mvc.perform(put(path).header("If-Match", etag).contentType("text/turtle").content(PROFILE))
            .andExpect(status().isPreconditionFailed());
    }

}
