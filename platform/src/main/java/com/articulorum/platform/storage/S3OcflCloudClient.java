package com.articulorum.platform.storage;

import java.io.IOException;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import io.ocfl.api.exception.OcflIOException;
import io.ocfl.api.exception.OcflJavaException;
import io.ocfl.core.storage.cloud.CloudClient;
import io.ocfl.core.storage.cloud.CloudObjectKey;
import io.ocfl.core.storage.cloud.HeadResult;
import io.ocfl.core.storage.cloud.KeyNotFoundException;
import io.ocfl.core.storage.cloud.ListResult;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * ocfl-java {@link CloudClient} on top of the synchronous AWS SDK {@link S3Client}.
 *
 * <p>ocfl-java ships an S3 client of its own ({@code ocfl-java-aws}), but it requires the
 * asynchronous CRT based client and the S3 Transfer Manager, which pull in native libraries,
 * are awkward with S3 implementations other than AWS (MinIO, Ceph) and do not work in GraalVM
 * native images. This implementation uses the same lightweight URL-connection based client as
 * the rest of the platform. Uploads use single {@code PutObject} requests, which S3 accepts up to
 * 5 GB per object.</p>
 *
 * <p>All keys are relative to an optional repository prefix inside the bucket, so several OCFL
 * repositories (or other data) can share one bucket.</p>
 */
@Slf4j
public class S3OcflCloudClient implements CloudClient {

    private static final int NOT_FOUND = 404;

    /** S3 deletes at most 1000 keys per request. */
    private static final int DELETE_BATCH = 1000;

    private final S3Client s3;

    private final String bucket;

    private final String repoPrefix;

    private final CloudObjectKey.Builder keys;

    /**
     * Creates the client.
     *
     * @param s3     the S3 client; owned by the caller
     * @param bucket the bucket holding the OCFL storage root
     * @param prefix the key prefix of the storage root inside the bucket, may be empty
     */
    public S3OcflCloudClient(final S3Client s3, final String bucket, final String prefix) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket cannot be blank");
        }
        this.bucket = bucket;
        this.repoPrefix = stripTrailingSlashes(prefix == null ? "" : prefix.trim());
        this.keys = CloudObjectKey.builder().prefix(repoPrefix);
    }

    static String stripTrailingSlashes(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    @Override
    public void close() {
        // the S3 client is owned by the caller
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public String prefix() {
        return repoPrefix;
    }

    @Override
    public Future<CloudObjectKey> uploadFileAsync(final Path srcPath, final String dstPath) {
        return uploadFileAsync(srcPath, dstPath, null);
    }

    @Override
    public Future<CloudObjectKey> uploadFileAsync(final Path srcPath, final String dstPath, final String contentType) {
        // uploads run synchronously; the returned future is already complete
        try {
            return CompletableFuture.completedFuture(uploadFile(srcPath, dstPath, contentType));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CloudObjectKey uploadFile(final Path srcPath, final String dstPath) {
        return uploadFile(srcPath, dstPath, null);
    }

    @Override
    public CloudObjectKey uploadFile(final Path srcPath, final String dstPath, final String contentType) {
        final CloudObjectKey key = keys.buildFromPath(dstPath);
        log.debug("Uploading {} to s3://{}/{}", srcPath, bucket, key);
        try {
            s3.putObject(putRequest(key, contentType, Files.size(srcPath)), RequestBody.fromFile(srcPath));
            return key;
        } catch (IOException e) {
            throw new OcflIOException("Unable to read " + srcPath, e);
        } catch (SdkException e) {
            throw new OcflJavaException("Unable to upload " + srcPath + " to " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public CloudObjectKey uploadBytes(final String dstPath, final byte[] bytes, final String contentType) {
        final CloudObjectKey key = keys.buildFromPath(dstPath);
        log.debug("Writing {} bytes to s3://{}/{}", bytes.length, bucket, key);
        try {
            s3.putObject(putRequest(key, contentType, bytes.length), RequestBody.fromBytes(bytes));
            return key;
        } catch (SdkException e) {
            throw new OcflJavaException("Unable to write " + key + ": " + e.getMessage(), e);
        }
    }

    private PutObjectRequest putRequest(final CloudObjectKey key, final String contentType, final long length) {
        final PutObjectRequest.Builder request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key.getKey())
            .contentLength(length);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        return request.build();
    }

    @Override
    public CloudObjectKey copyObject(final String srcPath, final String dstPath) {
        final CloudObjectKey source = keys.buildFromPath(srcPath);
        final CloudObjectKey destination = keys.buildFromPath(dstPath);
        log.debug("Copying s3://{}/{} to {}", bucket, source, destination);
        try {
            s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(source.getKey())
                .destinationBucket(bucket)
                .destinationKey(destination.getKey())
                .build());
            return destination;
        } catch (SdkException e) {
            throw notFoundOr(e, source, "Unable to copy " + source + " to " + destination);
        }
    }

    @Override
    public Path downloadFile(final String srcPath, final Path dstPath) {
        try (InputStream in = downloadStream(srcPath)) {
            if (dstPath.getParent() != null) {
                Files.createDirectories(dstPath.getParent());
            }
            Files.copy(in, dstPath, StandardCopyOption.REPLACE_EXISTING);
            return dstPath;
        } catch (IOException e) {
            throw new OcflIOException("Unable to download " + srcPath + " to " + dstPath, e);
        }
    }

    @Override
    public InputStream downloadStream(final String srcPath) {
        return get(srcPath, null);
    }

    @Override
    public InputStream downloadStreamRange(final String srcPath, final String range) {
        return get(srcPath, range);
    }

    private InputStream get(final String srcPath, final String range) {
        final CloudObjectKey key = keys.buildFromPath(srcPath);
        log.debug("Streaming s3://{}/{} range {}", bucket, key, range);
        try {
            final GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key.getKey());
            if (range != null) {
                request.range(range);
            }
            return s3.getObject(request.build());
        } catch (SdkException e) {
            throw notFoundOr(e, key, "Unable to download " + key);
        }
    }

    @Override
    public String downloadString(final String srcPath) {
        try (InputStream in = downloadStream(srcPath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcflIOException("Unable to download " + srcPath, e);
        }
    }

    @Override
    public HeadResult head(final String path) {
        final CloudObjectKey key = keys.buildFromPath(path);
        try {
            final HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key.getKey())
                .build());
            return new HeadResult()
                .setContentEncoding(response.contentEncoding())
                .setContentLength(response.contentLength())
                .setETag(response.eTag())
                .setLastModified(response.lastModified());
        } catch (SdkException e) {
            throw notFoundOr(e, key, "Unable to HEAD " + key);
        }
    }

    @Override
    public ListResult list(final String prefix) {
        return list(ListObjectsV2Request.builder().bucket(bucket).prefix(keys.buildFromPath(prefix).getKey()));
    }

    @Override
    public ListResult listDirectory(final String path) {
        return list(ListObjectsV2Request.builder().bucket(bucket).delimiter("/").prefix(directoryKey(path)));
    }

    @Override
    public boolean directoryExists(final String path) {
        try {
            final ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .delimiter("/")
                .prefix(directoryKey(path))
                .maxKeys(1)
                .build());
            return !response.contents().isEmpty() || !response.commonPrefixes().isEmpty();
        } catch (SdkException e) {
            throw new OcflJavaException("Unable to list " + path + ": " + e.getMessage(), e);
        }
    }

    private String directoryKey(final String path) {
        final String key = keys.buildFromPath(path).getKey();
        return key.isEmpty() || key.endsWith("/") ? key : key + "/";
    }

    @Override
    public void deletePath(final String path) {
        log.debug("Deleting s3://{}/{}", bucket, path);
        deleteKeys(list(path).getObjects().stream().map(listing -> listing.getKey().getKey()).toList());
    }

    @Override
    public void deleteObjects(final Collection<String> objectPaths) {
        deleteKeys(objectPaths.stream().filter(Objects::nonNull).map(path -> keys.buildFromPath(path).getKey())
            .toList());
    }

    private void deleteKeys(final List<String> objectKeys) {
        for (int start = 0; start < objectKeys.size(); start += DELETE_BATCH) {
            final List<ObjectIdentifier> batch = objectKeys.subList(start, Math.min(objectKeys.size(), start + DELETE_BATCH))
                .stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
            try {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(delete -> delete.objects(batch).quiet(true))
                    .build());
            } catch (SdkException e) {
                throw new OcflJavaException("Unable to delete " + batch.size() + " objects: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void safeDeleteObjects(final String... objectPaths) {
        safeDeleteObjects(Arrays.asList(objectPaths));
    }

    @Override
    public void safeDeleteObjects(final Collection<String> objectPaths) {
        try {
            deleteObjects(objectPaths);
        } catch (RuntimeException e) {
            log.error("Unable to clean up objects in bucket {}: {}", bucket, objectPaths, e);
        }
    }

    @Override
    public boolean bucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (SdkException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw new OcflJavaException("Unable to HEAD bucket " + bucket + ": " + e.getMessage(), e);
        }
    }

    private ListResult list(final ListObjectsV2Request.Builder request) {
        try {
            ListObjectsV2Response response = s3.listObjectsV2(request.build());
            final int prefixLength = prefixLength(response.prefix());
            final int repoPrefixLength = repoPrefix.isBlank() ? 0 : repoPrefix.length() + 1;
            final List<ListResult.ObjectListing> objects = new ArrayList<>();
            final List<ListResult.DirectoryListing> directories = new ArrayList<>();
            collect(response, prefixLength, repoPrefixLength, objects, directories);
            while (Boolean.TRUE.equals(response.isTruncated())) {
                response = s3.listObjectsV2(request.continuationToken(response.nextContinuationToken()).build());
                collect(response, prefixLength, repoPrefixLength, objects, directories);
            }
            return new ListResult().setObjects(objects).setDirectories(directories);
        } catch (SdkException e) {
            throw new OcflJavaException("Unable to list objects: " + e.getMessage(), e);
        }
    }

    private void collect(final ListObjectsV2Response response, final int prefixLength, final int repoPrefixLength,
            final List<ListResult.ObjectListing> objects, final List<ListResult.DirectoryListing> directories) {
        response.contents().forEach(object -> objects.add(new ListResult.ObjectListing()
            .setKey(keys.buildFromKey(object.key()))
            .setKeySuffix(object.key().substring(Math.min(prefixLength, object.key().length())))));
        response.commonPrefixes().stream()
            .filter(common -> common.prefix() != null)
            .forEach(common -> directories.add(new ListResult.DirectoryListing()
                .setPath(common.prefix().substring(Math.min(repoPrefixLength, common.prefix().length())))));
    }

    /**
     * Length of the part of a listed key that the listing prefix covers (including the separating
     * slash), so the remainder is relative to the listed directory.
     *
     * @param prefix the listing prefix, may be {@code null}
     * @return the length to strip
     */
    static int prefixLength(final String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        return prefix.endsWith("/") ? prefix.length() : prefix.length() + 1;
    }

    private RuntimeException notFoundOr(final SdkException e, final CloudObjectKey key, final String message) {
        if (isNotFound(e)) {
            return new KeyNotFoundException("Key " + key + " not found in bucket " + bucket, e);
        }
        return new OcflJavaException(message + ": " + e.getMessage(), e);
    }

    /**
     * Tells whether an SDK failure means that a key or bucket does not exist.
     *
     * @param e the failure
     * @return {@code true} for "not found" failures
     */
    static boolean isNotFound(final SdkException e) {
        if (e instanceof NoSuchKeyException || e instanceof NoSuchBucketException) {
            return true;
        }
        return e instanceof S3Exception s3e && s3e.statusCode() == NOT_FOUND;
    }

}
