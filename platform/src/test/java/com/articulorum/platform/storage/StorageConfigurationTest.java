package com.articulorum.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the helpers of {@link StorageConfiguration}.
 */
class StorageConfigurationTest {

    /** The S3 description names the bucket and key prefix. */
    @Test
    void describesS3Roots() {
        final StorageProperties properties = new StorageProperties();
        properties.getS3().setBucket("articulorum");
        properties.getS3().setKeyPrefix("ocfl/");

        assertThat(StorageConfiguration.describe(properties)).isEqualTo("ocfl:s3:articulorum/ocfl");

        properties.getS3().setKeyPrefix("");
        assertThat(StorageConfiguration.describe(properties)).isEqualTo("ocfl:s3:articulorum");
    }

    /** The file system description names the absolute root directory. */
    @Test
    void describesFileSystemRoots() {
        final StorageProperties properties = new StorageProperties();
        properties.setType(StorageType.FILESYSTEM);
        properties.getFilesystem().setRoot(Path.of("/data/ocfl"));

        assertThat(StorageConfiguration.describe(properties))
            .isEqualTo("ocfl:filesystem:" + Path.of("/data/ocfl").toAbsolutePath().normalize());
    }

    /** A missing bucket is created when allowed. */
    @Test
    void createsMissingBuckets() throws IOException {
        final InMemoryS3Client client = new InMemoryS3Client("articulorum", false);

        StorageConfiguration.ensureBucket(client, "articulorum", true);

        assertThat(client.bucketExists()).isTrue();
    }

    /** A missing bucket is an error when creation is disabled. */
    @Test
    void refusesMissingBucketsWhenCreationIsDisabled() {
        final InMemoryS3Client client = new InMemoryS3Client("articulorum", false);

        assertThatThrownBy(() -> StorageConfiguration.ensureBucket(client, "articulorum", false))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not exist");
    }

    /** Defaults: S3 storage with database object locks. */
    @Test
    void defaultsToS3WithDatabaseLocks() {
        final StorageProperties properties = new StorageProperties();

        assertThat(properties.getType()).isEqualTo(StorageType.S3);
        assertThat(properties.getOcfl().isDatabaseLock()).isTrue();
    }

}
