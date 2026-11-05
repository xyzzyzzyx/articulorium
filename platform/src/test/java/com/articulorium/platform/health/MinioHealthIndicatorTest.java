package com.articulorium.platform.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.platform.storage.InMemoryS3Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for {@link MinioHealthIndicator}.
 */
class MinioHealthIndicatorTest {

    /** Up when the bucket exists. */
    @Test
    void upWhenTheBucketExists() {
        final Health health = new MinioHealthIndicator(new InMemoryS3Client("articulorium", true), "http://minio:9000",
            "articulorium").health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("endpoint", "http://minio:9000")
            .containsEntry("bucket", "articulorium");
    }

    /** Down when the bucket is missing; AWS is reported when no endpoint is configured. */
    @Test
    void downWhenTheBucketIsMissing() {
        final Health health = new MinioHealthIndicator(new InMemoryS3Client("articulorium", false), null, "articulorium")
            .health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("endpoint", "aws");
    }

}
