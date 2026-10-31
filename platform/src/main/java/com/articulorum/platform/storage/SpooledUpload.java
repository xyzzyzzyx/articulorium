package com.articulorum.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.articulorum.platform.ldp.exception.PayloadTooLargeException;

/**
 * An uploaded binary copied to a local temporary file, with its size and SHA-256 digest.
 *
 * <p>Spooling decouples reading the request from writing to the store: the size is known before
 * the store is contacted (S3 requires it), the digest can be reported in the {@code Digest}
 * header, and a slow client never holds a store connection open. Callers must {@link #close()}
 * the upload to delete the temporary file.</p>
 *
 * @param file   the temporary file
 * @param size   the size in bytes
 * @param sha256 the lowercase hexadecimal SHA-256 digest
 */
public record SpooledUpload(Path file, long size, String sha256) implements AutoCloseable {

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Copies a stream to a temporary file, computing its digest and enforcing a size limit.
     *
     * @param in        the stream; it is not closed
     * @param maxBytes  the largest accepted size
     * @param directory the directory for the temporary file, or {@code null} for the default
     * @return the spooled upload
     * @throws IOException               when the stream or the file system fails
     * @throws PayloadTooLargeException  when the stream exceeds {@code maxBytes}
     */
    public static SpooledUpload spool(final InputStream in, final long maxBytes, final Path directory)
            throws IOException {
        final MessageDigest digest = newSha256Digest();
        final Path file = directory == null
            ? Files.createTempFile("articulorum-upload-", ".bin")
            : Files.createTempFile(Files.createDirectories(directory), "articulorum-upload-", ".bin");
        long size = 0;
        try (DigestInputStream digestIn = new DigestInputStream(in, digest);
                OutputStream out = Files.newOutputStream(file)) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = digestIn.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new SpooledUpload(file, size, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Deletes the temporary file. */
    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    }

}
