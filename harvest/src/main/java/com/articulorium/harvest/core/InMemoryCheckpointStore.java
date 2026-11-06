package com.articulorium.harvest.core;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checkpoints kept in memory, for tests and for embedding the harvester where no durable store is
 * needed.
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Instant> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Optional<Instant> load(final String sourceId) {
        return Optional.ofNullable(checkpoints.get(sourceId));
    }

    @Override
    public void save(final String sourceId, final Instant checkpoint) {
        checkpoints.put(sourceId, checkpoint);
    }

}
