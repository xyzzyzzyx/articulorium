package com.articulorum.platform.controller;

import org.springframework.http.MediaType;

import com.articulorum.platform.utility.RdfMediaType;

public class RdfXmlLdpControllerTest extends LdpControllerTest {

    public MediaType getAccept() {
        return RdfMediaType.APPLICATION_RDF_XML;
    }

    public MediaType getContentType() {
        return RdfMediaType.APPLICATION_RDF_XML;
    }

    public String getRdfType() {
        return "RDF/XML";
    }

}
