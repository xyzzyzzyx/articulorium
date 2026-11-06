package com.articulorium.harvest.source.oai;

import org.apache.jena.rdf.model.Resource;
import org.w3c.dom.Element;

/**
 * Maps the metadata of one OAI-PMH metadata format to RDF. Register more mappers (as Spring beans)
 * to harvest formats other than {@code oai_dc}.
 */
public interface OaiMetadataMapper {

    /**
     * Returns the metadata prefix this mapper reads ({@code oai_dc}, {@code mods}, ...).
     *
     * @return the prefix
     */
    String metadataPrefix();

    /**
     * Describes a record with its metadata.
     *
     * @param metadata the root element inside the record's {@code <metadata>}
     * @param record   the record's subject, in the model to add statements to
     * @throws IllegalArgumentException when the metadata cannot be read
     */
    void map(Element metadata, Resource record);

}
