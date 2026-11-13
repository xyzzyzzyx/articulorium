package com.articulorium.discovery.solr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.articulorium.discovery.config.DiscoveryProperties;
import com.articulorium.discovery.index.IndexUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Queries the Solr collection the index service keeps in sync, over Solr's JSON API (plain HTTP,
 * like the index service: no SolrJ).
 *
 * <p>Parameters are sent as a form ({@code POST}), so long filter lists do not hit URL length
 * limits and user input never needs URL encoding by hand.</p>
 */
public class SolrSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(SolrSearchClient.class);

    private final RestClient client;

    private final String collection;

    private final String handler;

    /**
     * Creates the client with a plain HTTP client (Solr is not registered in Consul).
     *
     * @param properties the settings
     */
    public SolrSearchClient(final DiscoveryProperties properties) {
        this(RestClient.builder(), properties);
    }

    /**
     * Creates the client.
     *
     * @param builder    the HTTP client builder
     * @param properties the settings
     */
    public SolrSearchClient(final RestClient.Builder builder, final DiscoveryProperties properties) {
        final String base = properties.getSolr().getBaseUrl();
        this.client = builder.clone().baseUrl(base.endsWith("/") ? base : base + "/").build();
        this.collection = properties.getSolr().getCollection();
        this.handler = properties.getSolr().getHandler();
    }

    /**
     * Runs a query.
     *
     * @param params the request parameters ({@code wt=json} is added)
     * @return Solr's response
     * @throws IndexUnavailableException when Solr cannot be reached or refuses the query
     */
    public JsonNode select(final MultiValueMap<String, String> params) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>(params);
        form.set("wt", "json");
        try {
            final JsonNode response = client.post()
                .uri("{collection}/{handler}", collection, handler)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) {
                throw new IndexUnavailableException("Solr answered without a body");
            }
            return response;
        } catch (RestClientException e) {
            LOG.warn("Solr query failed: {}", e.getMessage());
            throw new IndexUnavailableException("The search index is unavailable");
        }
    }

    /**
     * Reads the collection's field names (Schema API).
     *
     * @return its explicit fields and dynamic field patterns
     * @throws IndexUnavailableException when Solr cannot be reached
     */
    public Schema schema() {
        return new Schema(names(schemaList("fields"), "fields"), names(schemaList("dynamicfields"), "dynamicFields"));
    }

    private JsonNode schemaList(final String list) {
        try {
            final JsonNode response = client.get()
                .uri("{collection}/schema/{list}?wt=json", collection, list)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) {
                throw new IndexUnavailableException("Solr answered without a body");
            }
            return response;
        } catch (RestClientException e) {
            LOG.warn("Solr schema request failed: {}", e.getMessage());
            throw new IndexUnavailableException("The search index is unavailable");
        }
    }

    private static List<String> names(final JsonNode response, final String key) {
        final List<String> names = new ArrayList<>();
        for (final JsonNode field : response.path(key)) {
            if (field.path("name").isString()) {
                names.add(field.path("name").asString());
            }
        }
        return names;
    }

    /**
     * Reads one document by identifier.
     *
     * @param id the document identifier (the resource's public IRI)
     * @param fields the fields to return ({@code fl})
     * @return the document, empty when the collection has none with that identifier
     * @throws IndexUnavailableException when Solr cannot be reached
     */
    public Optional<JsonNode> get(final String id, final String fields) {
        final MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "{!term f=id}" + id);
        params.add("rows", "1");
        params.add("fl", fields);
        final JsonNode docs = select(params).path("response").path("docs");
        return docs.isArray() && !docs.isEmpty() ? Optional.of(docs.get(0)) : Optional.empty();
    }

    /**
     * The field names of a collection's schema.
     *
     * @param fields        explicit fields
     * @param dynamicFields dynamic field patterns, such as {@code *_str}
     */
    public record Schema(List<String> fields, List<String> dynamicFields) {
    }

}
