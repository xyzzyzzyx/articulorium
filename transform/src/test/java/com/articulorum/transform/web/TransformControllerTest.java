package com.articulorum.transform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.fieldmapping.FieldMapping;
import com.articulorum.transform.fieldmapping.FieldMappingTransformer;
import com.articulorum.transform.fieldmapping.FieldMappings;
import com.articulorum.transform.fieldmapping.FieldType;
import com.articulorum.transform.registry.TransformerRegistry;
import com.articulorum.transform.service.TransformService;
import com.articulorum.transform.spi.Transformer;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests the transformer REST API with a standalone MockMvc.
 */
class TransformControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        final StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("fieldMapping", new FieldMappingTransformer(new FieldMappings(java.util.Map.of(), List.of(
            new FieldMapping("title", List.of(List.of("http://purl.org/dc/terms/title")), FieldType.STRING)))));
        final TransformProperties properties = new TransformProperties();
        final TransformerRegistry registry = new TransformerRegistry(beans.getBeanProvider(Transformer.class), properties);
        mvc = MockMvcBuilders.standaloneSetup(new TransformController(registry, new TransformService(registry, properties)))
            .build();
    }

    /** Registered transformers are listed with their active flag. */
    @Test
    void listsTransformers() throws Exception {
        mvc.perform(get("/transformers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("field-mapping"))
            .andExpect(jsonPath("$[0].active").value(true));
        mvc.perform(get("/transformers/field-mapping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description").exists());
    }

    /** Unknown transformers are 404 problems. */
    @Test
    void unknownTransformersAreNotFound() throws Exception {
        mvc.perform(get("/transformers/nope")).andExpect(status().isNotFound());
        mvc.perform(post("/transformers/nope/preview").param("iri", "urn:x").content("<urn:x> <urn:p> 1 ."))
            .andExpect(status().isNotFound());
    }

    /** Posted Turtle is transformed without publishing anything. */
    @Test
    void previewsTurtle() throws Exception {
        mvc.perform(post("/transformers/field-mapping/preview")
                .param("iri", "http://example.org/b")
                .contentType("text/turtle")
                .content("<http://example.org/b> <http://purl.org/dc/terms/title> \"Moby Dick\" ."))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title[0]").value("Moby Dick"))
            .andExpect(jsonPath("$.id[0]").value("http://example.org/b"));
    }

    /** Malformed RDF is a 400 problem. */
    @Test
    void rejectsInvalidRdf() throws Exception {
        mvc.perform(post("/transformers/field-mapping/preview")
                .param("iri", "urn:x")
                .contentType("text/turtle")
                .content("this is not turtle"))
            .andExpect(status().isBadRequest());
    }

    /** The content type selects the RDF syntax; Turtle is the fallback. */
    @Test
    void choosesTheRdfSyntax() {
        assertThat(TransformController.langOf("application/ld+json; charset=UTF-8")).isEqualTo(Lang.JSONLD);
        assertThat(TransformController.langOf("application/n-triples")).isEqualTo(Lang.NTRIPLES);
        assertThat(TransformController.langOf(null)).isEqualTo(Lang.TURTLE);
        assertThat(TransformController.langOf("application/octet-stream")).isEqualTo(Lang.TURTLE);
    }

}
