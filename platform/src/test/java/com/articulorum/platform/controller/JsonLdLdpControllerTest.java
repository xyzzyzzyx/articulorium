package com.articulorum.platform.controller;

import org.springframework.http.MediaType;

import com.articulorum.platform.utility.RdfMediaType;

public class JsonLdLdpControllerTest extends LdpControllerTest {

    public MediaType getAccept() {
        return RdfMediaType.APPLICATION_JSON_LD;
    }

    public MediaType getContentType() {
        return RdfMediaType.APPLICATION_JSON_LD;
    }

    public String getRdfType() {
        return "JSON-LD";
    }

}
