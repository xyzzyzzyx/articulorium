package com.articulorium.harvest.core;

import java.time.Instant;
import java.util.Optional;

/**
 * Remembers where the next incremental harvest of each source starts.
 */
public interface CheckpointStore {

    /**
     * Reads a source's checkpoint.
     *
     * @param sourceId the source identifier
     * @return the checkpoint, or empty before the first complete harvest
     * @throws HarvestException when the store cannot be read
     */
    Optional<Instant> load(String sourceId);

    /**
     * Stores a source's checkpoint.
     *
     * @param sourceId   the source identifier
     * @param checkpoint where the next incremental harvest starts
     * @throws HarvestException when the store cannot be written
     */
    void save(String sourceId, Instant checkpoint);

}
