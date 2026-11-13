package com.articulorium.discovery.elasticsearch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.index.IndexQuery;
import com.articulorium.discovery.index.IndexResult;
import com.articulorium.discovery.index.IndexUnavailableException;
import com.articulorium.discovery.index.SearchIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The Elasticsearch index the index service keeps in sync, as the discovery layer's search index:
 * queried over Elasticsearch's search API (plain HTTP and JSON, like the Solr client: no client
 * library).
 */
public class ElasticsearchSearchIndex implements SearchIndex {

    /** Deepest result Elasticsearch pages to ({@code index.max_result_window}'s default). */
    static final int MAX_RESULT_WINDOW = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchSearchIndex.class);

    private final RestClient client;

    private final String index;

    /**
     * Creates the index with a plain HTTP client (Elasticsearch is not registered in Consul).
     *
     * @param properties the settings
     */
    public ElasticsearchSearchIndex(final DiscoveryProperties properties) {
        this(RestClient.builder(), properties);
    }

    /**
     * Creates the index.
     *
     * @param builder    the HTTP client builder
     * @param properties the settings
     */
    public ElasticsearchSearchIndex(final RestClient.Builder builder, final DiscoveryProperties properties) {
        final DiscoveryProperties.Elasticsearch settings = properties.getElasticsearch();
        final String base = settings.getBaseUrl();
        final RestClient.Builder configured = builder.clone().baseUrl(base.endsWith("/") ? base : base + "/");
        if (StringUtils.hasText(settings.getApiKey())) {
            configured.defaultHeader("Authorization", "ApiKey " + settings.getApiKey().trim());
        } else if (StringUtils.hasText(settings.getUsername())) {
            configured.defaultHeaders(headers -> headers.setBasicAuth(settings.getUsername(),
                settings.getPassword() == null ? "" : settings.getPassword(), StandardCharsets.UTF_8));
        }
        this.client = configured.build();
        this.index = settings.getIndex();
    }

    /**
     * Elasticsearch leaves fields it has not mapped yet out of searches instead of refusing them.
     *
     * @param field the field name
     * @return {@code true}
     */
    @Override
    public boolean hasField(final String field) {
        return true;
    }

    @Override
    public IndexResult search(final IndexQuery query) {
        if ((long) query.start() + query.rows() > MAX_RESULT_WINDOW) {
            // Elasticsearch refuses deeper pages; there is nothing to list beyond them
            LOG.debug("No results beyond the first {} are read from Elasticsearch", MAX_RESULT_WINDOW);
            return IndexResult.empty();
        }
        final JsonNode response = post(ElasticsearchRequests.search(query));
        final List<JsonNode> documents = new ArrayList<>();
        for (final JsonNode hit : response.path("hits").path("hits")) {
            if (hit.path("_source").isObject()) {
                documents.add(hit.path("_source"));
            }
        }
        final Map<String, List<IndexResult.Count>> facets = new LinkedHashMap<>();
        for (final IndexQuery.Facet facet : query.facets()) {
            final List<IndexResult.Count> values = new ArrayList<>();
            for (final JsonNode bucket : response.path("aggregations").path(facet.id())
                    .path(ElasticsearchRequests.VALUES).path("buckets")) {
                final JsonNode key = bucket.path("key_as_string").isString() ? bucket.path("key_as_string")
                    : bucket.path("key");
                if (key.isValueNode() && !key.isNull()) {
                    values.add(new IndexResult.Count(key.asString(), bucket.path("doc_count").asLong(0)));
                }
            }
            facets.put(facet.id(), values);
        }
        return new IndexResult(response.path("hits").path("total").path("value").asLong(0), documents, facets);
    }

    private JsonNode post(final Map<String, Object> body) {
        try {
            final JsonNode response = client.post()
                .uri("{index}/_search", index)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) {
                throw new IndexUnavailableException("Elasticsearch answered without a body");
            }
            return response;
        } catch (RestClientException e) {
            LOG.warn("Elasticsearch query failed: {}", e.getMessage());
            throw new IndexUnavailableException("The search index is unavailable");
        }
    }

}
