package com.articulorium.oai.catalog;

import java.time.Instant;
import java.util.List;

/**
 * A resource exposed as an OAI-PMH record.
 *
 * @param path     the resource path below the LDP root
 * @param modified when it last changed (its datestamp, at full precision)
 * @param setSpecs the sets it belongs to, outermost first
 */
public record CatalogRecord(String path, Instant modified, List<String> setSpecs) {

    /**
     * Copies the set specs.
     *
     * @param path     the path
     * @param modified the modification time
     * @param setSpecs the set specs
     */
    public CatalogRecord {
        setSpecs = List.copyOf(setSpecs);
    }

}
