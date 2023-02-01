package com.articulorum.platform.converter;

import com.articulorum.platform.utility.RdfMediaType;

public class RdfXmlHttpMessageConverter extends AbstractRdfHttpMessageConverter {

    public RdfXmlHttpMessageConverter() {
        super(RdfMediaType.APPLICATION_RDF_XML);
    }

    public String getRdfType() {
        return "RDF/XML";
    }

}