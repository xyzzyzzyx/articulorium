package com.articulorum.platform.converter;

import com.articulorum.platform.utility.RdfMediaType;

public class JsonLdHttpMessageConverter extends AbstractRdfHttpMessageConverter {

    public JsonLdHttpMessageConverter() {
        super(RdfMediaType.APPLICATION_JSON_LD);
    }

    public String getRdfType() {
        return "JSON-LD";
    }

}