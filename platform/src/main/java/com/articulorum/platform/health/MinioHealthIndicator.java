package com.articulorum.platform.health;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Reports whether the S3 compatible object store (MinIO in the docker compose stack) holding the
 * OCFL storage root is reachable and the bucket accessible with the configured credentials.
 */
public class MinioHealthIndicator extends AbstractHealthIndicator {

    private final S3Client client;

    private final String endpoint;

    private final String bucket;

    /**
     * Creates the indicator.
     *
     * @param client   the S3 client
     * @param endpoint the endpoint the client talks to (for the details), may be {@code null} for AWS
     * @param bucket   the bucket to check
     */
    public MinioHealthIndicator(final S3Client client, final String endpoint, final String bucket) {
        super("Object storage health check failed");
        this.client = client;
        this.endpoint = endpoint;
        this.bucket = bucket;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        builder.withDetail("endpoint", endpoint == null || endpoint.isBlank() ? "aws" : endpoint)
            .withDetail("bucket", bucket);
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        builder.up();
    }

}
