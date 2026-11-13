package com.articulorium.index.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Elasticsearch settings ({@code articulorium.elasticsearch.*}), used when
 * {@code articulorium.index.backend=elasticsearch}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.elasticsearch")
public class ElasticsearchProperties {

    /** Base URL of an Elasticsearch node or of a load balancer in front of the cluster. */
    private String baseUrl = "http://elasticsearch:9200";

    /** Index (or alias of one index) holding the documents. */
    private String index = "articulorium";

    /**
     * API key ({@code Authorization: ApiKey <key>}): the Base64 "encoded" value Elasticsearch
     * returns when the key is created. Used instead of {@link #username} when set.
     */
    private String apiKey;

    /** User of HTTP basic authentication; no credentials are sent when unset. */
    private String username;

    /** Password of {@link #username}. */
    private String password;

    /** Primary shards of an index created on start-up. */
    private int numberOfShards = 1;

    /** Replicas of an index created on start-up (0 on a single node). */
    private int numberOfReplicas = 1;

    /**
     * How often Elasticsearch makes updates visible in an index created on start-up
     * ({@code index.refresh_interval}); the counterpart of Solr's {@code commitWithin}.
     */
    private String refreshInterval = "1s";

    /**
     * Fields declared in the index mapping on start-up when they are missing, by name, with their
     * Elasticsearch field type. Every other field is mapped when its first document is indexed:
     * as text with an exact-value {@code <field>_str} copy, like Solr's {@code _default} config
     * set does. The fields the discovery layer sorts on and builds year facets from must be dates,
     * so they are declared up front. Existing fields are left alone.
     */
    private Map<String, String> fields = new LinkedHashMap<>();

}
