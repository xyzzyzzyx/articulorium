package com.articulorum.platform;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.articulorum.domain.vocabulary.Articulorum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The container of the community structure is created on start-up.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommunityRootIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void communityContainerExists() throws Exception {
        mvc.perform(get("/communities").accept("application/n-triples"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(Articulorum.COMMUNITY_ROOT)))
            .andExpect(content().string(containsString("\"Communities\"")));
        mvc.perform(get("/").accept("application/n-triples"))
            .andExpect(content().string(containsString("/communities>")));
    }

}
