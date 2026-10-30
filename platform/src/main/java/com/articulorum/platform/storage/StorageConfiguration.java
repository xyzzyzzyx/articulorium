package com.articulorum.platform.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.ocfl.api.OcflRepository;
import io.ocfl.core.OcflRepositoryBuilder;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.lock.ObjectLockBuilder;
import io.ocfl.core.storage.OcflStorage;
import io.ocfl.core.storage.OcflStorageBuilder;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Creates the OCFL storage layer selected by {@code articulorum.storage.type}.
 *
 * <p>S3 is the default ({@code matchIfMissing}); the docker compose stack points it at MinIO. Set
 * {@code articulorum.storage.type=filesystem} to keep the OCFL storage root on local disk
 * instead. Either way the platform talks to a single {@link OcflRepository} through the
 * {@link BinaryStore} interface. When a database is available, concurrent writers (several
 * platform instances) are coordinated through a lock table in it.</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

    private static final int NOT_FOUND = 404;

    /**
     * S3 client configured for the selected endpoint.
     *
     * @param properties storage settings
     * @return the client
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "articulorum.storage", name = "type", havingValue = "s3", matchIfMissing = true)
    public S3Client s3Client(final StorageProperties properties) {
        final StorageProperties.S3 s3 = properties.getS3();
        final S3ClientBuilder builder = S3Client.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(Region.of(s3.getRegion()))
            .credentialsProvider(credentials(s3))
            .forcePathStyle(s3.isPathStyle())
            // only compute/validate checksums when the operation requires them: maximises
            // compatibility with S3 implementations other than AWS
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (StringUtils.hasText(s3.getEndpoint())) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        return builder.build();
    }

    /**
     * OCFL storage in an S3 bucket; verifies (and by default creates) the bucket on start-up.
     *
     * @param client     the S3 client
     * @param properties storage settings
     * @return the storage
     * @throws IOException when the bucket is unavailable
     */
    @Bean
    @ConditionalOnProperty(prefix = "articulorum.storage", name = "type", havingValue = "s3", matchIfMissing = true)
    public OcflStorage s3OcflStorage(final S3Client client, final StorageProperties properties) throws IOException {
        final StorageProperties.S3 s3 = properties.getS3();
        ensureBucket(client, s3.getBucket(), s3.isCreateBucket());
        return OcflStorageBuilder.builder()
            .cloud(new S3OcflCloudClient(client, s3.getBucket(), s3.getKeyPrefix()))
            .build();
    }

    /**
     * OCFL storage in a local directory, created when missing.
     *
     * @param properties storage settings
     * @return the storage
     * @throws IOException when the directory cannot be created
     */
    @Bean
    @ConditionalOnProperty(prefix = "articulorum.storage", name = "type", havingValue = "filesystem")
    public OcflStorage fileSystemOcflStorage(final StorageProperties properties) throws IOException {
        final Path root = properties.getFilesystem().getRoot().toAbsolutePath().normalize();
        Files.createDirectories(root);
        return OcflStorageBuilder.builder().fileSystem(root).build();
    }

    /**
     * The OCFL repository.
     *
     * <p>New repositories use the hashed n-tuple storage layout (extension 0004), which spreads
     * objects evenly whatever their identifiers look like.</p>
     *
     * @param storage    the storage root
     * @param properties storage settings
     * @param dataSource the platform database, used for the object lock table when enabled
     * @return the repository; closed when the context shuts down
     * @throws IOException when the work directory cannot be created
     */
    @Bean(destroyMethod = "close")
    public OcflRepository ocflRepository(final OcflStorage storage, final StorageProperties properties,
            final ObjectProvider<DataSource> dataSource) throws IOException {
        final StorageProperties.Ocfl ocfl = properties.getOcfl();
        final Path workDirectory = ocfl.getWorkDirectory() != null
            ? ocfl.getWorkDirectory()
            : Path.of(System.getProperty("java.io.tmpdir"), "articulorum-ocfl-work");
        Files.createDirectories(workDirectory);
        final OcflRepositoryBuilder builder = new OcflRepositoryBuilder()
            .defaultLayoutConfig(new HashedNTupleLayoutConfig())
            .storage(storage)
            .workDir(workDirectory);
        final DataSource database = dataSource.getIfAvailable();
        if (ocfl.isDatabaseLock() && database != null) {
            try {
                builder.objectLock(new ObjectLockBuilder()
                    .dataSource(database)
                    .tableName(ocfl.getLockTable())
                    .build());
                log.info("OCFL object locks are kept in database table {}", ocfl.getLockTable());
            } catch (RuntimeException e) {
                // ocfl-java supports PostgreSQL, MariaDB and H2 lock tables only
                log.warn("Database object locks are unavailable ({}); using in-memory locks, so only one "
                    + "platform instance may write to the storage root", e.getMessage());
                builder.objectLock(new ObjectLockBuilder().waitTime(30, TimeUnit.SECONDS).build());
            }
        }
        return builder.build();
    }

    /**
     * The OCFL backed {@link BinaryStore}.
     *
     * @param repository the OCFL repository
     * @param properties storage settings
     * @return the store
     */
    @Bean
    public BinaryStore binaryStore(final OcflRepository repository, final StorageProperties properties) {
        return new OcflBinaryStore(repository, describe(properties));
    }

    /**
     * Describes the configured storage root for logs and health details.
     *
     * @param properties storage settings
     * @return for example {@code ocfl:s3:articulorum/ocfl}
     */
    static String describe(final StorageProperties properties) {
        if (properties.getType() == StorageType.FILESYSTEM) {
            return "ocfl:filesystem:" + properties.getFilesystem().getRoot().toAbsolutePath().normalize();
        }
        final StorageProperties.S3 s3 = properties.getS3();
        final String prefix = S3OcflCloudClient.stripTrailingSlashes(s3.getKeyPrefix() == null ? "" : s3.getKeyPrefix());
        return "ocfl:s3:" + s3.getBucket() + (prefix.isEmpty() ? "" : "/" + prefix);
    }

    /**
     * Verifies that a bucket exists, creating it when allowed.
     *
     * @param client       the S3 client
     * @param bucket       the bucket
     * @param createBucket whether a missing bucket may be created
     * @throws IOException when the bucket is missing and may not be created, or S3 is unreachable
     */
    static void ensureBucket(final S3Client client, final String bucket, final boolean createBucket) throws IOException {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != NOT_FOUND) {
                throw new IOException("Unable to access bucket " + bucket + ": " + e.getMessage(), e);
            }
            if (!createBucket) {
                throw new IOException("Bucket " + bucket + " does not exist", e);
            }
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created S3 bucket {}", bucket);
            } catch (SdkException createFailure) {
                throw new IOException("Unable to create bucket " + bucket + ": " + createFailure.getMessage(),
                    createFailure);
            }
        } catch (SdkException e) {
            throw new IOException("Unable to reach S3 for bucket " + bucket + ": " + e.getMessage(), e);
        }
    }

    private static AwsCredentialsProvider credentials(final StorageProperties.S3 s3) {
        if (StringUtils.hasText(s3.getAccessKey()) && StringUtils.hasText(s3.getSecretKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

}
