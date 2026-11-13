package com.articulorium.index.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the index service ({@code articulorium.index.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.index")
public class IndexProperties {

    /**
     * The search index documents are written to. Solr unless set; the discovery service must read
     * the same backend ({@code articulorium.discovery.index.backend}).
     */
    private Backend backend = Backend.SOLR;

    /** Search index backends. */
    public enum Backend {

        /** Apache Solr ({@code articulorium.solr.*}). */
        SOLR,

        /** Elasticsearch ({@code articulorium.elasticsearch.*}). */
        ELASTICSEARCH

    }

}
