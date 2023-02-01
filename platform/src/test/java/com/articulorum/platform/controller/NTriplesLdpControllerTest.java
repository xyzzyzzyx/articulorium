package com.articulorum.platform.controller;

import org.springframework.http.MediaType;

import com.articulorum.platform.utility.RdfMediaType;

public class NTriplesLdpControllerTest extends LdpControllerTest {

    public MediaType getAccept() {
        return RdfMediaType.APPLICATION_N_TRIPLES;
    }

    public MediaType getContentType() {
        return RdfMediaType.APPLICATION_N_TRIPLES;
    }

    public String getRdfType() {
        return "N-TRIPLES";
    }

}
