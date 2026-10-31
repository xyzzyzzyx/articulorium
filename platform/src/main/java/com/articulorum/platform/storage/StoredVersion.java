package com.articulorum.platform.storage;

import java.time.Instant;
import java.util.Map;

/**
 * One immutable version of an OCFL object.
 *
 * @param versionId the version identifier, for example {@code v2}
 * @param created   when the version was created
 * @param agent     who created it, may be {@code null}
 * @param message   why it was created, may be {@code null}
 * @param files     the logical paths of the version's files mapped to their OCFL content digests
 */
public record StoredVersion(String versionId, Instant created, String agent, String message,
        Map<String, String> files) {

    /**
     * Tells whether the version contains a file.
     *
     * @param logicalPath the logical path
     * @return {@code true} when the file is part of the version
     */
    public boolean contains(final String logicalPath) {
        return files != null && files.containsKey(logicalPath);
    }

}
