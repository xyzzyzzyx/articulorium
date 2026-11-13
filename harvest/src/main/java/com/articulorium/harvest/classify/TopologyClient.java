package com.articulorium.harvest.classify;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

/**
 * Builds the item of an accepted record with the topology API ({@code POST /topologies/{id}/instances}),
 * with the reviewer's credentials, so the item follows the topology's content model exactly as one
 * built from the user interface (PCDM types, {@code art:topology}, its members and files containers).
 * Child levels are not built: a harvested record arrives without pages or parts. It also finds
 * topologies and saves the ones automatic acceptance creates ({@link TopologyProposer}).
 */
public class TopologyClient {

    private final RestClient client;

    private final String base;

    /**
     * Creates the client.
     *
     * @param client      the HTTP client (load balanced when configured)
     * @param topologyUrl where the topology API is reached
     */
    public TopologyClient(final RestClient client, final String topologyUrl) {
        this.client = client;
        this.base = topologyUrl.endsWith("/") ? topologyUrl : topologyUrl + "/";
    }

    /**
     * Tells whether a topology exists.
     *
     * @param id            the topology
     * @param authorization the caller's credentials
     * @return its definition, or empty when there is none
     * @throws PlacementException when the topology API cannot answer
     */
    public Optional<JsonNode> get(final String id, final String authorization) {
        try {
            return Optional.ofNullable(client.get()
                .uri(UriComponentsBuilder.fromUriString(base + "topologies/{id}").buildAndExpand(id).encode().toUri())
                .headers(headers -> auth(headers, authorization))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw failure("read the topology " + id, e);
        } catch (RestClientException e) {
            throw failure("read the topology " + id, e);
        }
    }

    /**
     * Finds a topology by its id or, failing that, by its name (whatever the case).
     *
     * @param id            the id
     * @param name          the name
     * @param authorization the caller's credentials
     * @return the topology, as an existing topology of a suggestion; empty when there is none
     * @throws PlacementException when the topology API cannot answer
     */
    public Optional<Suggestion.Topology> find(final String id, final String name, final String authorization) {
        final JsonNode topologies;
        try {
            topologies = client.get().uri(URI.create(base + "topologies"))
                .headers(headers -> auth(headers, authorization))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientException e) {
            throw failure("list the topologies", e);
        }
        Suggestion.Topology named = null;
        if (topologies != null) {
            for (final JsonNode topology : topologies) {
                if (!topology.path("id").isString()) {
                    continue;
                }
                final String found = topology.path("id").asString();
                final String foundName = topology.path("name").isString() ? topology.path("name").asString() : found;
                if (found.equals(id)) {
                    return Optional.of(new Suggestion.Topology(Suggestion.Topology.EXISTING, found, foundName, null));
                }
                if (named == null && name != null && foundName.strip().equalsIgnoreCase(name.strip())) {
                    named = new Suggestion.Topology(Suggestion.Topology.EXISTING, found, foundName, null);
                }
            }
        }
        return Optional.ofNullable(named);
    }

    /**
     * Saves a new custom topology ({@code POST /topologies}).
     *
     * @param topology      the topology
     * @param authorization the caller's credentials
     * @return the saved topology's id
     * @throws PlacementException when the topology API refuses it (not valid, the id taken, no access)
     */
    public String create(final JsonNode topology, final String authorization) {
        final String id = topology.path("id").asString();
        try {
            final JsonNode saved = client.post().uri(URI.create(base + "topologies"))
                .headers(headers -> auth(headers, authorization))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(topology)
                .retrieve()
                .body(JsonNode.class);
            return saved != null && saved.path("id").isString() ? saved.path("id").asString() : id;
        } catch (RestClientException e) {
            throw failure("save the topology " + id, e);
        }
    }

    /**
     * Builds the root resource of a topology (without children) in a collection.
     *
     * @param topology      the topology's definition
     * @param parent        the collection's path
     * @param title         the item's title
     * @param slug          the item's suggested name
     * @param authorization the caller's credentials
     * @return the item's path
     * @throws PlacementException when the build fails (the platform's status is kept)
     */
    public String build(final JsonNode topology, final String parent, final String title, final String slug,
            final String authorization) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        collectKeys(topology.path("root"), counts, true);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", parent);
        body.put("title", title);
        body.put("slug", slug);
        body.put("counts", counts);
        final String id = topology.path("id").asString();
        try {
            final JsonNode result = client.post()
                .uri(UriComponentsBuilder.fromUriString(base + "topologies/{id}/instances").buildAndExpand(id).encode()
                    .toUri())
                .headers(headers -> auth(headers, authorization))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            if (result == null || !result.path("path").isString()) {
                throw new PlacementException(502, "The topology API built no item");
            }
            return result.path("path").asString();
        } catch (RestClientException e) {
            throw failure("build the topology " + id, e);
        }
    }

    private static void collectKeys(final JsonNode node, final Map<String, Integer> counts, final boolean root) {
        if (!root && node.path("key").isString()) {
            counts.put(node.path("key").asString(), 0);
        }
        for (final JsonNode child : node.path("children")) {
            collectKeys(child.path("node"), counts, false);
        }
    }

    private static PlacementException failure(final String what, final RestClientException e) {
        final int status = e instanceof RestClientResponseException response ? response.getStatusCode().value() : 503;
        return new PlacementException(status == 400 ? 409 : status, "The topology API failed to " + what + ": "
            + (e instanceof RestClientResponseException response
                ? response.getStatusCode().value() + " " + response.getResponseBodyAsString().strip()
                : e.getMessage()), e);
    }

    private static void auth(final HttpHeaders headers, final String authorization) {
        if (StringUtils.hasText(authorization)) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    /** A placement step that failed, with the HTTP status that describes it. */
    public static class PlacementException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        /**
         * Creates the exception.
         *
         * @param status  the status
         * @param message the diagnostic
         */
        public PlacementException(final int status, final String message) {
            super(message);
            this.status = status;
        }

        /**
         * Creates the exception.
         *
         * @param status  the status
         * @param message the diagnostic
         * @param cause   the cause
         */
        public PlacementException(final int status, final String message, final Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        /**
         * Returns the status.
         *
         * @return the HTTP status
         */
        public int getStatus() {
            return status;
        }

    }

}
