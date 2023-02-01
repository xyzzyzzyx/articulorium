package com.articulorum.platform.converter;

import com.articulorum.platform.utility.RdfMediaType;

public class RdfJsonHttpMessageConverter extends AbstractRdfHttpMessageConverter {

    public RdfJsonHttpMessageConverter() {
        super(RdfMediaType.APPLICATION_RDF_JSON);
    }

    public String getRdfType() {
        return "RDF/JSON";
    }

}