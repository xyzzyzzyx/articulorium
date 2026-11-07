package com.articulorium.ai.topics;

/**
 * What to model.
 *
 * @param path      the community or collection
 * @param maxTopics the largest number of topics wanted, {@code null} for the configured maximum
 */
public record TopicModelRequest(String path, Integer maxTopics) {
}
