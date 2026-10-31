package com.articulorum.index.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Solr settings ({@code articulorum.solr.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.solr")
public class SolrProperties {

    /** Base URL of a Solr node, including the {@code /solr} context. */
    private String baseUrl = "http://solr:8983/solr";

    /** Collection holding the documents. */
    private String collection = "articulorum";

    /** Shards of a collection created on start-up. */
    private int numShards = 1;

    /** Replicas of a collection created on start-up. */
    private int replicationFactor = 1;

    /** Config set of a collection created on start-up. */
    private String configSet = "_default";

    /** Milliseconds within which Solr makes updates visible ({@code commitWithin}). */
    private int commitWithin = 1000;

}
