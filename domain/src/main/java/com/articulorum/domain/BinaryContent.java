package com.articulorum.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Metadata describing the stored bytes of an LDP Non-RDF Source.
 *
 * <p>The bytes themselves live in the OCFL storage layer (on the local file system or in S3
 * compatible object storage); this embeddable records which OCFL object and which version of it
 * hold the current bytes, and what they are. Older versions remain available from the object's
 * history.</p>
 */
@Embeddable
public class BinaryContent {

    @Column(name = "binary_storage_key", length = 1024)
    private String storageKey;

    @Column(name = "binary_storage_version", length = 32)
    private String storageVersion;

    @Column(name = "binary_content_type", length = 255)
    private String contentType;

    @Column(name = "binary_filename", length = 1024)
    private String filename;

    @Column(name = "binary_size")
    private Long size;

    @Column(name = "binary_sha256", length = 64)
    private String sha256;

    /** Creates empty metadata; required by JPA and Jackson. */
    /**
     * Logical path of the bytes inside the storage object; {@code null} means
     * {@value #DEFAULT_CONTENT_PATH} (the object holds only this binary). Binaries inside an
     * archival group share the group's object and keep their bytes below their own directory.
     */
    @Column(name = "binary_logical_path", length = 2048)
    private String logicalPath;

    /** Logical path of the bytes of a binary that owns its storage object. */
    public static final String DEFAULT_CONTENT_PATH = "content";

    public BinaryContent() {
        // for JPA and Jackson
    }

    /**
     * Creates binary metadata.
     *
     * @param storageKey     the identifier of the OCFL object holding the bytes
     * @param storageVersion the OCFL version holding the current bytes (for example {@code v3})
     * @param contentType    the media type supplied by the client
     * @param filename       the original file name, or {@code null}
     * @param size           the size in bytes
     * @param sha256         the lowercase hexadecimal SHA-256 digest of the bytes
     */
    public BinaryContent(final String storageKey, final String storageVersion, final String contentType,
            final String filename, final long size, final String sha256) {
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.storageVersion = storageVersion;
        this.contentType = contentType;
        this.filename = filename;
        this.size = size;
        this.sha256 = sha256;
    }

    /** @return the identifier of the OCFL object holding the bytes */
    public String getStorageKey() {
        return storageKey;
    }

    /** @param storageKey the identifier of the OCFL object holding the bytes */
    public void setStorageKey(final String storageKey) {
        this.storageKey = storageKey;
    }

    /** @return the OCFL version holding the current bytes, or {@code null} for the object's head */
    public String getStorageVersion() {
        return storageVersion;
    }

    /** @param storageVersion the OCFL version holding the current bytes */
    public void setStorageVersion(final String storageVersion) {
        this.storageVersion = storageVersion;
    }

    /** @return the media type of the bytes */
    public String getContentType() {
        return contentType;
    }

    /** @param contentType the media type of the bytes */
    public void setContentType(final String contentType) {
        this.contentType = contentType;
    }

    /** @return the original file name, or {@code null} */
    public String getFilename() {
        return filename;
    }

    /** @param filename the original file name */
    public void setFilename(final String filename) {
        this.filename = filename;
    }

    /** @return the size in bytes */
    public Long getSize() {
        return size;
    }

    /** @param size the size in bytes */
    public void setSize(final Long size) {
        this.size = size;
    }

    /** @return the lowercase hexadecimal SHA-256 digest */
    public String getSha256() {
        return sha256;
    }

    /** @param sha256 the lowercase hexadecimal SHA-256 digest */
    public void setSha256(final String sha256) {
        this.sha256 = sha256;
    }

    /**
     * Returns the stored logical path, {@code null} for the default.
     *
     * @return the logical path or {@code null}
     */
    public String getLogicalPath() {
        return logicalPath;
    }

    /**
     * Sets the logical path of the bytes inside the storage object.
     *
     * @param logicalPath the path, or {@code null} (or {@value #DEFAULT_CONTENT_PATH}) for the
     *                    default
     */
    public void setLogicalPath(final String logicalPath) {
        this.logicalPath = logicalPath == null || DEFAULT_CONTENT_PATH.equals(logicalPath) ? null : logicalPath;
    }

    /**
     * Returns the logical path of the bytes inside the storage object.
     *
     * @return the stored path, or {@value #DEFAULT_CONTENT_PATH}
     */
    public String contentPath() {
        return logicalPath == null ? DEFAULT_CONTENT_PATH : logicalPath;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BinaryContent)) {
            return false;
        }
        final BinaryContent that = (BinaryContent) other;
        return Objects.equals(storageKey, that.storageKey)
            && Objects.equals(storageVersion, that.storageVersion)
            && Objects.equals(contentType, that.contentType)
            && Objects.equals(filename, that.filename)
            && Objects.equals(size, that.size)
            && Objects.equals(sha256, that.sha256)
            && Objects.equals(logicalPath, that.logicalPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storageKey, storageVersion, contentType, filename, size, sha256, logicalPath);
    }

}
