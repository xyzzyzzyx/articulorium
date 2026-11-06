package com.articulorium.harvest.source;

import java.util.Objects;

import org.springframework.web.client.RestClient;

/**
 * Hands out the HTTP client builder shared by the sources: plain (never load balanced, sources are
 * outside the service mesh), with the harvester's timeouts and {@code User-Agent}.
 */
public final class SourceRestClients {

    private final RestClient.Builder builder;

    /**
     * Wraps a builder.
     *
     * @param builder the builder
     */
    public SourceRestClients(final RestClient.Builder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /**
     * Returns a copy of the builder.
     *
     * @return the builder
     */
    public RestClient.Builder builder() {
        return builder.clone();
    }

}
