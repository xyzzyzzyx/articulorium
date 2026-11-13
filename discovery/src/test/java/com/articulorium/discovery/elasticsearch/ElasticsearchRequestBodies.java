package com.articulorium.discovery.elasticsearch;

import java.util.List;

import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures the JSON bodies of requests to a mocked Elasticsearch.
 */
public final class ElasticsearchRequestBodies {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ElasticsearchRequestBodies() {
    }

    /**
     * A matcher that records the request's body.
     *
     * @param into where the body is added
     * @return the matcher
     */
    public static RequestMatcher capture(final List<JsonNode> into) {
        return request -> into.add(MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString()));
    }

    /**
     * Parses JSON.
     *
     * @param json the text
     * @return the tree
     */
    public static JsonNode json(final String json) {
        return MAPPER.readTree(json);
    }

    /**
     * The text values of an array.
     *
     * @param array the array
     * @return its values
     */
    public static List<String> strings(final JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

}
