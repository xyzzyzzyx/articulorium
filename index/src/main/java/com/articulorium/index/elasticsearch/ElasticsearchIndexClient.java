package com.articulorium.index.elasticsearch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.backend.IndexStatus;
import com.articulorium.index.config.ElasticsearchProperties;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Minimal client for the Elasticsearch REST APIs used by the index service.
 *
 * <p>Like the Solr client, it talks plain HTTP and JSON: no Elasticsearch client library, whose
 * version would have to follow the cluster's, and nothing GraalVM native images need hints for.
 * Updates become visible with the index's refresh interval (one second unless configured), the
 * counterpart of Solr's {@code commitWithin}.</p>
 */
public class ElasticsearchIndexClient implements DocumentIndex {

    /** Name of the backend. */
    public static final String NAME = "elasticsearch";

    /** Longest document identifier Elasticsearch accepts, in bytes. */
    static final int MAX_ID_BYTES = 512;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() { };

    private final RestClient client;

    private final ElasticsearchProperties properties;

    /**
     * Creates the client.
     *
     * @param builder    the auto-configured REST client builder
     * @param properties Elasticsearch settings
     */
    public ElasticsearchIndexClient(final RestClient.Builder builder, final ElasticsearchProperties properties) {
        final RestClient.Builder configured = builder.clone().baseUrl(properties.getBaseUrl());
        if (StringUtils.hasText(properties.getApiKey())) {
            configured.defaultHeader("Authorization", "ApiKey " + properties.getApiKey().trim());
        } else if (StringUtils.hasText(properties.getUsername())) {
            configured.defaultHeaders(headers -> headers.setBasicAuth(properties.getUsername(),
                properties.getPassword() == null ? "" : properties.getPassword(), StandardCharsets.UTF_8));
        }
        this.client = configured.build();
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String target() {
        return "Elasticsearch index " + properties.getIndex();
    }

    @Override
    public Map<String, Object> location() {
        final Map<String, Object> location = new LinkedHashMap<>();
        location.put("baseUrl", properties.getBaseUrl());
        location.put("index", properties.getIndex());
        return location;
    }

    /**
     * Creates the configured index, with the mappings of {@link ElasticsearchMappings}, unless it
     * exists. An existing index (or alias) is used as it is: its mappings are the operator's.
     *
     * @return {@code true} when the index was created
     */
    @Override
    public boolean ensureIndex() {
        if (exists()) {
            return false;
        }
        try {
            client.put()
                .uri("/{index}", properties.getIndex())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ElasticsearchMappings.index(properties))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.BadRequest e) {
            // another instance of the service created it in the meantime
            if (e.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                return false;
            }
            throw e;
        }
    }

    private boolean exists() {
        try {
            client.head().uri("/{index}", properties.getIndex()).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /**
     * Declares the configured fields in the index mapping unless they exist.
     *
     * @return the names of the fields that were added
     */
    @Override
    public List<String> ensureFields() {
        final Map<String, String> fields = properties.getFields();
        if (fields.isEmpty()) {
            return List.of();
        }
        final Map<String, Object> response = client.get()
            .uri("/{index}/_mapping", properties.getIndex())
            .retrieve()
            .body(JSON_OBJECT);
        final Map<?, ?> existing = declaredFields(response);
        final Map<String, Object> additions = new LinkedHashMap<>();
        fields.forEach((name, type) -> {
            if (!existing.containsKey(name)) {
                additions.put(name, ElasticsearchMappings.field(type));
            }
        });
        if (additions.isEmpty()) {
            return List.of();
        }
        client.put()
            .uri("/{index}/_mapping", properties.getIndex())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("properties", additions))
            .retrieve()
            .toBodilessEntity();
        return List.copyOf(additions.keySet());
    }

    /** The answer is keyed by the concrete index's name, which differs from an alias's. */
    private static Map<?, ?> declaredFields(final Map<String, Object> response) {
        if (response != null && !response.isEmpty() && response.values().iterator().next() instanceof Map<?, ?> index
                && index.get("mappings") instanceof Map<?, ?> mappings
                && mappings.get("properties") instanceof Map<?, ?> fields) {
            return fields;
        }
        return Map.of();
    }

    @Override
    public void add(final Map<String, ?> document) {
        client.put()
            .uri("/{index}/_doc/{id}", properties.getIndex(), documentId(String.valueOf(document.get("id"))))
            .contentType(MediaType.APPLICATION_JSON)
            .body(document)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void deleteById(final String id) {
        try {
            client.delete()
                .uri("/{index}/_doc/{id}", properties.getIndex(), documentId(id))
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // not indexed (any more): nothing to delete
        }
    }

    @Override
    public IndexStatus status() {
        final Map<String, Object> response = client.get()
            .uri("/{index}/_count", properties.getIndex())
            .retrieve()
            .body(JSON_OBJECT);
        final long documents = response != null && response.get("count") instanceof Number count ? count.longValue() : -1;
        return new IndexStatus(true, IndexStatus.OK, documents);
    }

    /**
     * The Elasticsearch identifier ({@code _id}) of a document: its {@code id} (a resource IRI), or
     * a digest of it when it is longer than Elasticsearch accepts. Documents are always found by
     * their {@code id} field, so the digest never shows.
     *
     * @param id the document identifier
     * @return the identifier, at most {@value #MAX_ID_BYTES} bytes long
     */
    static String documentId(final String id) {
        final byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_ID_BYTES) {
            return id;
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

}
