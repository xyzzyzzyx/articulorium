package com.articulorum.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Versioned file storage for the content of LDP resources, organised as OCFL objects.
 *
 * <p>Each resource with stored content owns one object (see {@link StorageKeys#objectId}). An
 * object holds a small set of logical files ({@link StorageKeys#CONTENT},
 * {@link StorageKeys#DESCRIPTION}) and an immutable history: every {@link #write write} creates a
 * new version that keeps the files it does not replace. Readers address a specific version, so a
 * database row that records the version it refers to always reads consistent bytes even while
 * newer versions are written.</p>
 *
 * <p>Implementations translate storage failures into {@link IOException}s and missing objects,
 * versions or files into {@link BinaryNotFoundException}s.</p>
 */
public interface BinaryStore {

    /**
     * A file to add to a new version.
     *
     * @param logicalPath the path of the file inside the object
     * @param source      a local file holding the bytes; it is not modified or deleted
     */
    record SourceFile(String logicalPath, Path source) {
    }

    /**
     * Who made a version and why.
     *
     * @param agent   the agent creating the version
     * @param message a short description of the change
     */
    record VersionNote(String agent, String message) {
    }

    /**
     * Writes a new version of an object, creating the object when it does not exist yet.
     *
     * <p>The listed files are added or replaced and the {@code inline} file, when given, is
     * written from memory; every other file of the previous version is kept unchanged.</p>
     *
     * @param objectId the object identifier
     * @param files    files to add or replace
     * @param inline   a small file to write from memory (for example a description snapshot), may
     *                 be {@code null}
     * @param note     author and message of the version
     * @return the new version
     * @throws IOException when the version cannot be written
     */
    StoredVersion write(String objectId, List<SourceFile> files, InlineFile inline, VersionNote note)
        throws IOException;

    /**
     * A small file written from memory.
     *
     * @param logicalPath the path of the file inside the object
     * @param content     the bytes
     */
    record InlineFile(String logicalPath, byte[] content) {
    }

    /**
     * Opens a file of an object version.
     *
     * @param objectId    the object identifier
     * @param versionId   the version, or {@code null} for the most recent one
     * @param logicalPath the file
     * @return a stream the caller must close
     * @throws BinaryNotFoundException when the object, version or file does not exist
     * @throws IOException             when the store cannot be read
     */
    InputStream open(String objectId, String versionId, String logicalPath) throws IOException;

    /**
     * Lists the versions of an object, oldest first.
     *
     * @param objectId the object identifier
     * @return the versions; empty when the object does not exist
     * @throws IOException when the store cannot be read
     */
    List<StoredVersion> versions(String objectId) throws IOException;

    /**
     * Describes the most recent version of an object.
     *
     * @param objectId the object identifier
     * @return the head version, or empty when the object does not exist
     * @throws IOException when the store cannot be read
     */
    Optional<StoredVersion> head(String objectId) throws IOException;

    /**
     * Makes an earlier version the most recent one again, discarding every later version. Used to
     * undo a write whose database transaction rolled back.
     *
     * @param objectId  the object identifier
     * @param versionId the version to return to
     * @throws IOException when the object cannot be modified
     */
    void rollback(String objectId, String versionId) throws IOException;

    /**
     * Permanently deletes an object and its history. Purging a missing object is not an error.
     *
     * @param objectId the object identifier
     * @throws IOException when the object cannot be deleted
     */
    void purge(String objectId) throws IOException;

    /**
     * Tells whether an object exists.
     *
     * @param objectId the object identifier
     * @return {@code true} when it exists
     * @throws IOException when the store cannot be queried
     */
    boolean exists(String objectId) throws IOException;

    /**
     * Validates an object against the OCFL specification, re-computing the digests of its content
     * files.
     *
     * @param objectId the object identifier
     * @return the validation outcome
     * @throws BinaryNotFoundException when the object does not exist
     * @throws IOException             when the store cannot be read
     */
    ValidationReport validate(String objectId) throws IOException;

    /**
     * Describes the store for logs and health information.
     *
     * @return for example {@code ocfl:filesystem:/data/ocfl} or {@code ocfl:s3:articulorum}
     */
    String describe();

}
