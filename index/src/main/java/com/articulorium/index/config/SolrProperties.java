package com.articulorium.index.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Solr settings ({@code articulorium.solr.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.solr")
public class SolrProperties {

    /** Base URL of a Solr node, including the {@code /solr} context. */
    private String baseUrl = "http://solr:8983/solr";

    /** Collection holding the documents. */
    private String collection = "articulorium";

    /** Shards of a collection created on start-up. */
    private int numShards = 1;

    /** Replicas of a collection created on start-up. */
    private int replicationFactor = 1;

    /** Config set of a collection created on start-up. */
    private String configSet = "_default";

    /** Milliseconds within which Solr makes updates visible ({@code commitWithin}). */
    private int commitWithin = 1000;

    /**
     * Fields declared in the collection's schema on start-up when they are missing, by name, with
     * their Solr field type. The {@code _default} config set only creates a field when the first
     * document carrying it is indexed ("schemaless"); sorting or faceting on a field that does not
     * exist yet fails, so the fields the discovery layer sorts and facets on are declared up front.
     * Existing fields are left alone.
     */
    private Map<String, String> fields = new LinkedHashMap<>();

}
