package com.articulorum.platform.controller;

import static com.articulorum.platform.controller.LdpController.ALREADY_EXISTS_TEMPLATE;
import static com.articulorum.platform.controller.LdpController.NOT_FOUND_TEMPLATE;
import static com.articulorum.platform.controller.LdpController.PARENT_NOT_FOUND_TEMPLATE;
import static com.articulorum.platform.controller.LdpController.ROOT;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;

import com.articulorum.domain.Container;
import com.articulorum.platform.utility.ContainerUtility;
import com.articulorum.platform.utility.PrefixUtility;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public abstract class LdpControllerTest extends AbstractLdpControllerTest {

    Model model;

    Container container;

    @BeforeAll
    public void initSchema() throws IOException {
        PrefixUtility.readNamespaceCSV(namespaces.getInputStream())
            .peek(PrefixUtility::putPrefix);
    }

    @BeforeEach
    public void initRoot() throws IOException {
        model = ModelFactory.createDefaultModel();
        model.read(root.getInputStream(), EMPTY, "TURTLE");
        container = ContainerUtility.fromModel(model);
        container.setId(UUID.randomUUID());
        container.setPath(EMPTY);
    }

    @Test
    public void shouldGetRoot() throws Exception {
        when(containerService.findByPath(EMPTY))
            .thenReturn(Optional.of(container));

        // TODO: assert content
        mockMvc.perform(get(ROOT)
            .accept(getAccept())
            .contentType(getContentType()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(getContentType()));
    }

    @Test
    public void shouldGet() throws Exception {
        container.setPath("test");
        when(containerService.findByPath("test"))
            .thenReturn(Optional.of(container));

        // TODO: assert content
        mockMvc.perform(get("/test")
            .accept(getAccept())
            .contentType(getContentType()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(getContentType()));
    }

    @Test
    public void shouldGetNotFound() throws Exception {
        container.setPath("test");
        when(containerService.findByPath("test"))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/test")
            .accept(getAccept())
            .contentType(getContentType()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(format(NOT_FOUND_TEMPLATE, "test")));
    }

    @Test
    public void shouldPost() throws Exception {
        when(containerService.existsByPath("test"))
            .thenReturn(false);

        when(containerService.findByPath(EMPTY))
            .thenReturn(Optional.of(container));

        // TODO: assert content
        mockMvc.perform(post(ROOT)
            .accept(getAccept())
            .content(getContent())
            .contentType(getContentType())
            .header("slug", "test"))
                .andExpect(status().isCreated());
    }

    @Test
    public void shouldPostAlreadyExists() throws Exception {
        when(containerService.existsByPath("test"))
            .thenReturn(true);

        mockMvc.perform(post(ROOT)
            .accept(getAccept())
            .content(getContent())
            .contentType(getContentType())
            .header("slug", "test"))
                .andExpect(status().isConflict())
                .andExpect(content().string(format(ALREADY_EXISTS_TEMPLATE, "test")));
    }

    @Test
    public void shouldPostParentNotFound() throws Exception {
        when(containerService.existsByPath(anyString()))
            .thenReturn(false);
        
        when(containerService.findByPath("test"))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/test")
            .accept(getAccept())
            .content(getContent())
            .contentType(getContentType()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(format(NOT_FOUND_TEMPLATE, "test")));
    }

    @Test
    public void shouldDelete() throws Exception {
        when(containerService.findByPath("test"))
            .thenReturn(Optional.of(container));

        when(containerService.findByPath(EMPTY))
            .thenReturn(Optional.of(container));

        mockMvc.perform(delete("/test"))
            .andExpect(status().isNoContent());
    }

    @Test
    public void shouldDeleteNotFound() throws Exception {
        when(containerService.findByPath("test"))
            .thenReturn(Optional.empty());

        when(containerService.findByPath(EMPTY))
            .thenReturn(Optional.of(container));

        mockMvc.perform(delete("/test"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(format(NOT_FOUND_TEMPLATE, "test")));
    }

    @Test
    public void shouldDeleteParentNotFound() throws Exception {
        when(containerService.findByPath("test"))
            .thenReturn(Optional.of(container));

        when(containerService.findByPath(EMPTY))
            .thenReturn(Optional.empty());

        mockMvc.perform(delete("/test"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(format(PARENT_NOT_FOUND_TEMPLATE, EMPTY)));
    }

    @Test
    public void shouldDeleteMethodNotAllowed() throws Exception {
        mockMvc.perform(delete(ROOT))
            .andExpect(status().isMethodNotAllowed());
    }

    private String getContent() throws IOException {
        try (StringWriter out = new StringWriter()) {
            model.write(out, getRdfType());
            return out.toString();
        }
    }

}
