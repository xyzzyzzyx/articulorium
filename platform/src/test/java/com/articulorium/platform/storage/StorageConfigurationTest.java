package com.articulorium.platform.storage;

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
        properties.getS3().setBucket("articulorium");
        properties.getS3().setKeyPrefix("ocfl/");

        assertThat(StorageConfiguration.describe(properties)).isEqualTo("ocfl:s3:articulorium/ocfl");

        properties.getS3().setKeyPrefix("");
        assertThat(StorageConfiguration.describe(properties)).isEqualTo("ocfl:s3:articulorium");
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
        final InMemoryS3Client client = new InMemoryS3Client("articulorium", false);

        StorageConfiguration.ensureBucket(client, "articulorium", true);

        assertThat(client.bucketExists()).isTrue();
    }

    /** A missing bucket is an error when creation is disabled. */
    @Test
    void refusesMissingBucketsWhenCreationIsDisabled() {
        final InMemoryS3Client client = new InMemoryS3Client("articulorium", false);

        assertThatThrownBy(() -> StorageConfiguration.ensureBucket(client, "articulorium", false))
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
