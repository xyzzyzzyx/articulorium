package com.articulorum.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

/**
 * Linked Data Platform behaviour settings ({@code articulorum.ldp.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.ldp")
public class LdpProperties {

    /** Largest RDF request body accepted by POST, PUT and PATCH. */
    private DataSize maxRdfSize = DataSize.ofMegabytes(10);

    /**
     * Agent recorded as creator and last modifier ({@code fedora:createdBy},
     * {@code fedora:lastModifiedBy}) when a request is not authenticated.
     */
    private String defaultAgent = "anonymous";

    /** Largest number of results a single {@code fcr:search} request may return. */
    private int maxSearchResults = 1000;

}
