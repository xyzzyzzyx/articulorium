package com.articulorum.platform.controller;

import com.articulorum.platform.service.ContainerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureRestDocs
@WebMvcTest(LdpController.class)
public abstract class AbstractLdpControllerTest {

    @Value("classpath:namespaces.csv")
    Resource namespaces;

    @Value("classpath:root.ttl")
    Resource root;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ContainerService containerService;

    abstract MediaType getAccept();

    abstract MediaType getContentType();

    abstract String getRdfType();

}
