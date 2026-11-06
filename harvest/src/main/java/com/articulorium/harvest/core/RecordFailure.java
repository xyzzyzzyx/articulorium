package com.articulorium.harvest.core;

import java.util.Objects;

/**
 * A record that could not be harvested or ingested.
 *
 * @param identifier the record's identifier in its source (or a description of where it was when
 *                   the identifier itself could not be read)
 * @param reason     why, safe to show to operators (no credentials)
 */
public record RecordFailure(String identifier, String reason) {

    /**
     * Creates a failure.
     *
     * @param identifier the identifier
     * @param reason     the reason
     */
    public RecordFailure {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(reason, "reason");
    }

}
