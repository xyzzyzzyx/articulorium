package com.articulorum.topology.web;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.topology.platform.PlatformClient;
import com.articulorum.topology.platform.PlatformException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the topology API over the full application context with a mocked platform client.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TopologyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlatformClient platform;

    @Test
    void listsAndReadsTopologies() throws Exception {
        when(platform.rdf(eq("topologies"), any())).thenReturn(Optional.empty());

        mvc.perform(get("/topologies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem("book")))
            .andExpect(jsonPath("$[*].id", hasItem("postcards")));
        mvc.perform(get("/topologies/newspaper"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.root.children[0].node.key").value("issue"))
            .andExpect(jsonPath("$.builtIn").value(true));
        when(platform.bytes(eq("topologies/nope"), any())).thenReturn(Optional.empty());
        mvc.perform(get("/topologies/nope")).andExpect(status().isNotFound());
    }

    @Test
    void validatesCustomTopologies() throws Exception {
        mvc.perform(post("/topologies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"Bad Id\",\"name\":\"\",\"root\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.length()").value(3));
        mvc.perform(put("/topologies/x").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"y\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/topologies").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"book\",\"name\":\"B\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/topologies/book")).andExpect(status().isBadRequest());
    }

    @Test
    void buildsInstances() throws Exception {
        when(platform.create(anyString(), anyString(), anyString(), anyList(), any()))
            .thenAnswer(call -> ResourcePaths.child(call.getArgument(0), call.getArgument(1)));

        mvc.perform(post("/topologies/postcards/instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parent\":\"communities/c\",\"title\":\"Cards\",\"slug\":\"cards\",\"counts\":{\"postcard\":1}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.path").value("communities/c/cards"))
            .andExpect(jsonPath("$.resources[*]", hasItem("communities/c/cards/members/postcard-1/members/front")));
        mvc.perform(post("/topologies/book/instances").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void platformRefusalsKeepTheirStatus() throws Exception {
        when(platform.create(anyString(), anyString(), anyString(), anyList(), any()))
            .thenThrow(new PlatformException(401, "requires authentication", null));

        mvc.perform(post("/topologies/book/instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Locked\"}"))
            .andExpect(status().isUnauthorized());
    }

}
