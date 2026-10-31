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

    /**
     * Logical path of the N-Triples snapshot of a resource's technical metadata (size, digest,
     * media type and file name of a binary) at the time a version was written, so that mementos
     * of binaries describe the bytes they hold.
     */
    public static final String TECHNICAL = "technical.nt";

    /**
     * Directory of the members of a resource inside an archival group's object. Each member owns
     * {@code members/<name>/}, which holds only the fixed file names above and its own
     * {@code members/} directory, so member names can never collide with the files.
     */
    public static final String MEMBERS_DIRECTORY = "members";

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
     * Returns the directory inside an object that holds the files of a resource.
     *
     * <p>The resource owning the object (a standalone resource or the archival group itself)
     * keeps its files at the top level; a member of an archival group at the relative path
     * {@code a/b} keeps them in {@code members/a/members/b/}.</p>
     *
     * @param relativePath the path of the resource relative to the object owner, empty for the
     *                     owner itself
     * @return the directory, empty or ending with {@code /}
     */
    public static String resourceDirectory(final String relativePath) {
        final String normalized = ResourcePaths.normalize(relativePath);
        if (normalized.isEmpty()) {
            return "";
        }
        final StringBuilder directory = new StringBuilder();
        for (final String segment : normalized.split("/")) {
            directory.append(MEMBERS_DIRECTORY).append('/').append(segment).append('/');
        }
        return directory.toString();
    }

    /**
     * Returns the logical path of a resource's description snapshot inside an object.
     *
     * @param relativePath the path relative to the object owner, empty for the owner
     * @return the logical path
     */
    public static String descriptionPath(final String relativePath) {
        return resourceDirectory(relativePath) + DESCRIPTION;
    }

    /**
     * Returns the logical path of a binary's bytes inside an object.
     *
     * @param relativePath the path relative to the object owner, empty for the owner
     * @return the logical path
     */
    public static String contentPath(final String relativePath) {
        return resourceDirectory(relativePath) + CONTENT;
    }

    /**
     * Returns the logical path of a binary's technical metadata snapshot inside an object.
     *
     * @param relativePath the path relative to the object owner, empty for the owner
     * @return the logical path
     */
    public static String technicalPath(final String relativePath) {
        return resourceDirectory(relativePath) + TECHNICAL;
    }

    /**
     * Returns the path of a resource relative to an ancestor (or itself).
     *
     * @param ancestor the ancestor path
     * @param path     the resource path, equal to or below {@code ancestor}
     * @return the relative path, empty when both are equal
     * @throws IllegalArgumentException when {@code path} is not below {@code ancestor}
     */
    public static String relativePath(final String ancestor, final String path) {
        final String a = ResourcePaths.normalize(ancestor);
        final String p = ResourcePaths.normalize(path);
        if (a.equals(p)) {
            return "";
        }
        if (!ResourcePaths.isSelfOrDescendant(p, a)) {
            throw new IllegalArgumentException(p + " is not below " + a);
        }
        return a.isEmpty() ? p : p.substring(a.length() + 1);
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
