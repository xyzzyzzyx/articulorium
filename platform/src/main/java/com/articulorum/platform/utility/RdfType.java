package com.articulorum.platform.utility;

import com.articulorum.domain.Element;

public class RdfType {

    public final static Element RDF_SOURCE = new Element(RdfPredicate.TYPE, LdpPredicate.RDF_SOURCE);
    public final static Element CONTAINER = new Element(RdfPredicate.TYPE, LdpPredicate.CONTAINER);
    public final static Element BASIC_CONTAINER = new Element(RdfPredicate.TYPE, LdpPredicate.BASIC_CONTAINER);
    public final static Element DIRECT_CONTAINER = new Element(RdfPredicate.TYPE, LdpPredicate.DIRECT_CONTAINER);
    public final static Element INDIRECT_CONTAINER = new Element(RdfPredicate.TYPE, LdpPredicate.INDIRECT_CONTAINER);

}