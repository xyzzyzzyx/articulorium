package com.articulorium.harvest.source.oai;

import com.articulorium.harvest.source.DublinCore;
import com.articulorium.harvest.source.Xml;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

/**
 * Maps unqualified Dublin Core ({@code oai_dc}, which every OAI-PMH data provider supports) to
 * {@code dcterms:} properties, keeping {@code xml:lang} as language tags.
 */
@Component
public class OaiDcMapper implements OaiMetadataMapper {

    /** The {@code oai_dc} container namespace. */
    static final String OAI_DC = "http://www.openarchives.org/OAI/2.0/oai_dc/";

    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    @Override
    public String metadataPrefix() {
        return "oai_dc";
    }

    @Override
    public void map(final Element metadata, final Resource record) {
        if (!"dc".equals(metadata.getLocalName()) || !OAI_DC.equals(metadata.getNamespaceURI())) {
            throw new IllegalArgumentException("Expected an oai_dc:dc element, found {" + metadata.getNamespaceURI()
                + "}" + metadata.getLocalName());
        }
        final Model model = record.getModel();
        for (final Element element : Xml.children(metadata)) {
            if (!DublinCore.DC_ELEMENTS.equals(element.getNamespaceURI())) {
                continue;
            }
            final String value = element.getTextContent().strip();
            if (value.isEmpty()) {
                continue;
            }
            DublinCore.element(element.getLocalName()).ifPresent(iri -> {
                final Property property = model.createProperty(iri);
                final String language = DublinCore.languageTag(element.getAttributeNS(XML_NS, "lang"));
                record.addProperty(property, language.isEmpty()
                    ? model.createLiteral(value)
                    : model.createLiteral(value, language));
            });
        }
    }

}
