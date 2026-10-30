package com.articulorum.platform.health;

import com.articulorum.platform.storage.BinaryStore;
import com.articulorum.platform.storage.StorageKeys;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the OCFL storage layer can be read.
 *
 * <p>The check looks up a probe object identifier, which makes ocfl-java resolve the object's
 * location in the storage root and read from it (the local file system or the S3 bucket). The
 * probe object normally does not exist; any answer, yes or no, proves the storage is reachable.</p>
 */
public class OcflHealthIndicator extends AbstractHealthIndicator {

    /** Identifier looked up by every check. */
    static final String PROBE_OBJECT_ID = StorageKeys.OBJECT_ID_PREFIX + ".well-known/health";

    private final BinaryStore store;

    /**
     * Creates the indicator.
     *
     * @param store the storage layer
     */
    public OcflHealthIndicator(final BinaryStore store) {
        super("OCFL storage health check failed");
        this.store = store;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        builder.withDetail("storage", store.describe());
        store.exists(PROBE_OBJECT_ID);
        builder.up();
    }

}
