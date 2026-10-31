package com.articulorum.platform.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.articulorum.platform.ldp.exception.PayloadTooLargeException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpooledUpload}.
 */
class SpooledUploadTest {

    /** SHA-256 of the ASCII string "hello". */
    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test
    void spoolsBytesWithSizeAndDigest() throws Exception {
        final byte[] bytes = "hello".getBytes(StandardCharsets.US_ASCII);
        try (SpooledUpload upload = SpooledUpload.spool(new ByteArrayInputStream(bytes), 100, null)) {
            assertEquals(5, upload.size());
            assertEquals(HELLO_SHA256, upload.sha256());
            assertArrayEquals(bytes, Files.readAllBytes(upload.file()));
        }
    }

    @Test
    void closeDeletesTheTemporaryFile() throws Exception {
        final SpooledUpload upload = SpooledUpload.spool(new ByteArrayInputStream(new byte[3]), 100, null);
        final Path file = upload.file();
        upload.close();
        assertFalse(Files.exists(file));
    }

    @Test
    void spoolsEmptyStreams() throws Exception {
        try (SpooledUpload upload = SpooledUpload.spool(new ByteArrayInputStream(new byte[0]), 100, null)) {
            assertEquals(0, upload.size());
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", upload.sha256());
        }
    }

    @Test
    void rejectsStreamsOverTheLimit() {
        assertThrows(PayloadTooLargeException.class,
            () -> SpooledUpload.spool(new ByteArrayInputStream(new byte[101]), 100, null));
    }

    @Test
    void usesTheConfiguredDirectory() throws Exception {
        final Path directory = Files.createTempDirectory("spool-test");
        try (SpooledUpload upload = SpooledUpload.spool(new ByteArrayInputStream(new byte[1]), 10, directory.resolve("nested"))) {
            assertEquals(directory.resolve("nested"), upload.file().getParent());
        }
    }

}
