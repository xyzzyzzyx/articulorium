package com.articulorum.platform.storage;

import java.util.regex.Pattern;

import com.articulorum.domain.rdf.ResourcePaths;

/**
 * Naming rules of the OCFL storage layer: object identifiers, logical file paths and version
 * identifiers.
 *
 * <p>Every LDP resource that has stored content owns one OCFL object whose identifier is
 * {@value #OBJECT_ID_PREFIX} followed by the resource path, for example
 * {@code info:articulorum/books/cover.png}. Inside the object the bytes of a non-RDF source are
 * kept as {@value #CONTENT} and a snapshot of the resource's client-managed triples as
 * {@value #DESCRIPTION}. OCFL itself maps identifiers to storage locations (hashed n-tuple
 * layout), so paths of any length or character set are safe.</p>
 */
public final class StorageKeys {

    /** Prefix of every object identifier, following Fedora's {@code info:fedora/} convention. */
    public static final String OBJECT_ID_PREFIX = "info:articulorum/";

    /** Logical path of the bytes of a non-RDF source. */
    public static final String CONTENT = "content";

    /** Logical path of the N-Triples snapshot of a resource's client-managed triples. */
    public static final String DESCRIPTION = "description.nt";

    private static final Pattern VERSION = Pattern.compile("v[0-9]{1,9}");

    private StorageKeys() {
        // utility class
    }

    /**
     * Returns the identifier of the OCFL object holding a resource's content.
     *
     * @param path the resource path; the root container cannot own an object
     * @return the object identifier
     * @throws IllegalArgumentException for the root container
     */
    public static String objectId(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("The root container has no storage object");
        }
        return OBJECT_ID_PREFIX + normalized;
    }

    /**
     * Validates an object identifier produced by {@link #objectId(String)}.
     *
     * @param objectId the identifier
     * @return the identifier
     * @throws IllegalArgumentException when it is not an Articulorum object identifier
     */
    public static String validate(final String objectId) {
        if (objectId == null || !objectId.startsWith(OBJECT_ID_PREFIX) || objectId.length() == OBJECT_ID_PREFIX.length()
                || objectId.contains("..") || objectId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid storage object identifier: " + objectId);
        }
        return objectId;
    }

    /**
     * Tells whether a string is a well formed OCFL version identifier such as {@code v3}.
     *
     * @param versionId the candidate, may be {@code null}
     * @return {@code true} when it is a version identifier
     */
    public static boolean isVersionId(final String versionId) {
        return versionId != null && VERSION.matcher(versionId).matches() && !"v0".equals(versionId);
    }

}
