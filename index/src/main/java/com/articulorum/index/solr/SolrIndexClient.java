package com.articulorum.index.solr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorum.index.config.SolrProperties;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal client for the Solr JSON APIs used by the index service.
 *
 * <p>Talking to Solr over plain HTTP keeps the service free of SolrJ, whose HTTP/2 client pins a
 * Jetty version that conflicts with the one managed by Spring Boot, and makes it trivially
 * compatible with GraalVM native images.</p>
 */
@Component
public class SolrIndexClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;

    private final SolrProperties properties;

    /**
     * Creates the client.
     *
     * @param builder    the auto-configured REST client builder
     * @param properties Solr settings
     */
    public SolrIndexClient(final RestClient.Builder builder, final SolrProperties properties) {
        this.client = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
    }

    /**
     * Lists the collections of the Solr cluster.
     *
     * @return the collection names
     */
    public List<String> listCollections() {
        final Map<String, Object> response = client.get()
            .uri("/admin/collections?action=LIST&wt=json")
            .retrieve()
            .body(JSON_OBJECT);
        final List<String> names = new ArrayList<>();
        if (response != null && response.get("collections") instanceof Collection<?> collections) {
            collections.forEach(name -> names.add(String.valueOf(name)));
        }
        return names;
    }

    /**
     * Creates the configured collection unless it exists.
     *
     * @return {@code true} when the collection was created
     */
    public boolean ensureCollection() {
        if (listCollections().contains(properties.getCollection())) {
            return false;
        }
        client.get()
            .uri(uri -> uri.path("/admin/collections")
                .queryParam("action", "CREATE")
                .queryParam("name", properties.getCollection())
                .queryParam("numShards", properties.getNumShards())
                .queryParam("replicationFactor", properties.getReplicationFactor())
                .queryParam("collection.configName", properties.getConfigSet())
                .queryParam("wt", "json")
                .build())
            .retrieve()
            .toBodilessEntity();
        return true;
    }

    /**
     * Pings the collection through Solr's ping handler.
     *
     * @return the status Solr reports, {@code OK} when the collection is healthy
     * @throws org.springframework.web.client.RestClientException when Solr cannot be reached or
     *         the collection does not exist
     */
    public String ping() {
        final Map<String, Object> response = client.get()
            .uri(uri -> uri.path("/{collection}/admin/ping").queryParam("wt", "json").build(properties.getCollection()))
            .retrieve()
            .body(JSON_OBJECT);
        return response == null ? "UNKNOWN" : String.valueOf(response.getOrDefault("status", "UNKNOWN"));
    }

    /**
     * Counts the documents of the collection.
     *
     * @return the number of documents, or {@code -1} when Solr returned no count
     */
    public long countDocuments() {
        final Map<String, Object> response = client.get()
            .uri(uri -> uri.path("/{collection}/select")
                .queryParam("q", "*:*")
                .queryParam("rows", 0)
                .queryParam("wt", "json")
                .build(properties.getCollection()))
            .retrieve()
            .body(JSON_OBJECT);
        if (response != null && response.get("response") instanceof Map<?, ?> body
                && body.get("numFound") instanceof Number count) {
            return count.longValue();
        }
        return -1;
    }

    /**
     * Adds or replaces a document.
     *
     * @param document the document; its {@code id} field identifies it
     */
    public void add(final Map<String, ?> document) {
        client.post()
            .uri(uri -> uri.path("/{collection}/update")
                .queryParam("commitWithin", properties.getCommitWithin())
                .queryParam("wt", "json")
                .build(properties.getCollection()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(List.of(document))
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * Deletes a document.
     *
     * @param id the document identifier
     */
    public void deleteById(final String id) {
        final Map<String, Object> command = new LinkedHashMap<>();
        command.put("delete", Map.of("id", id));
        client.post()
            .uri(uri -> uri.path("/{collection}/update")
                .queryParam("commitWithin", properties.getCommitWithin())
                .queryParam("wt", "json")
                .build(properties.getCollection()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(command)
            .retrieve()
            .toBodilessEntity();
    }

}
