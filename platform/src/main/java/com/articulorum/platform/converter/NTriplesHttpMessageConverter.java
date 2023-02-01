package com.articulorum.platform.converter;

import com.articulorum.platform.utility.RdfMediaType;

public class NTriplesHttpMessageConverter extends AbstractRdfHttpMessageConverter {

    public NTriplesHttpMessageConverter() {
        super(RdfMediaType.APPLICATION_N_TRIPLES);
    }

    public String getRdfType() {
        return "N-TRIPLES";
    }

}