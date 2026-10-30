package com.articulorum.platform.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Minimal in-memory implementation of the S3 operations the OCFL cloud client uses (single
 * bucket, no pagination, ranges of the form {@code bytes=a-b}). It lets the S3 storage path run
 * against a real OCFL repository in unit tests without MinIO or Docker.
 */
public final class InMemoryS3Client implements S3Client {

    private final String bucket;

    private boolean bucketExists;

    private final Map<String, byte[]> objects = new ConcurrentSkipListMap<>();

    /**
     * Creates the client.
     *
     * @param bucket       the only bucket it knows
     * @param bucketExists whether the bucket exists initially
     */
    public InMemoryS3Client(final String bucket, final boolean bucketExists) {
        this.bucket = bucket;
        this.bucketExists = bucketExists;
    }

    /** @return the stored keys, sorted */
    Set<String> keys() {
        return objects.keySet();
    }

    /** @return whether the bucket exists */
    boolean bucketExists() {
        return bucketExists;
    }

    private void checkBucket(final String name) {
        if (!bucketExists || !bucket.equals(name)) {
            throw NoSuchBucketException.builder().message("No such bucket " + name).statusCode(404).build();
        }
    }

    private byte[] get(final String key) {
        final byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("No such key " + key).statusCode(404).build();
        }
        return bytes;
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public HeadBucketResponse headBucket(final HeadBucketRequest request) {
        checkBucket(request.bucket());
        return HeadBucketResponse.builder().build();
    }

    @Override
    public CreateBucketResponse createBucket(final CreateBucketRequest request) {
        bucketExists = true;
        return CreateBucketResponse.builder().build();
    }

    @Override
    public PutObjectResponse putObject(final PutObjectRequest request, final RequestBody body) {
        checkBucket(request.bucket());
        try (InputStream in = body.contentStreamProvider().newStream()) {
            objects.put(request.key(), in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return PutObjectResponse.builder().build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(final GetObjectRequest request) {
        checkBucket(request.bucket());
        byte[] bytes = get(request.key());
        if (request.range() != null && request.range().startsWith("bytes=")) {
            final String[] bounds = request.range().substring("bytes=".length()).split("-", -1);
            final int start = Integer.parseInt(bounds[0]);
            final int end = bounds[1].isEmpty() ? bytes.length - 1 : Math.min(Integer.parseInt(bounds[1]), bytes.length - 1);
            bytes = java.util.Arrays.copyOfRange(bytes, start, end + 1);
        }
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) bytes.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    @Override
    public HeadObjectResponse headObject(final HeadObjectRequest request) {
        checkBucket(request.bucket());
        final byte[] bytes = get(request.key());
        return HeadObjectResponse.builder().contentLength((long) bytes.length).eTag("\"" + bytes.length + "\"")
            .lastModified(Instant.now()).build();
    }

    @Override
    public CopyObjectResponse copyObject(final CopyObjectRequest request) {
        checkBucket(request.sourceBucket());
        objects.put(request.destinationKey(), get(request.sourceKey()).clone());
        return CopyObjectResponse.builder().build();
    }

    @Override
    public DeleteObjectsResponse deleteObjects(final DeleteObjectsRequest request) {
        checkBucket(request.bucket());
        for (final ObjectIdentifier id : request.delete().objects()) {
            objects.remove(id.key());
        }
        return DeleteObjectsResponse.builder().build();
    }

    @Override
    public ListObjectsV2Response listObjectsV2(final ListObjectsV2Request request) {
        checkBucket(request.bucket());
        final String prefix = request.prefix() == null ? "" : request.prefix();
        final String delimiter = request.delimiter();
        final List<S3Object> contents = new ArrayList<>();
        final Set<String> common = new LinkedHashSet<>();
        for (final Map.Entry<String, byte[]> entry : objects.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            final int cut = delimiter == null ? -1 : key.indexOf(delimiter, prefix.length());
            if (cut >= 0) {
                common.add(key.substring(0, cut + delimiter.length()));
            } else {
                contents.add(S3Object.builder().key(key).size((long) entry.getValue().length).build());
            }
        }
        final int max = request.maxKeys() == null ? Integer.MAX_VALUE : request.maxKeys();
        return ListObjectsV2Response.builder()
            .prefix(prefix)
            .contents(contents.stream().limit(max).toList())
            .commonPrefixes(common.stream().limit(max).map(p -> CommonPrefix.builder().prefix(p).build()).toList())
            .isTruncated(false)
            .build();
    }

}
