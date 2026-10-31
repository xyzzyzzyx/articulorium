package com.articulorum.platform.health;

import javax.sql.DataSource;

import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.storage.BinaryStore;
import com.articulorum.platform.storage.StorageProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Registers the platform's health indicators, reported by {@code /actuator/health} under the
 * component names {@code database}, {@code ocfl} and {@code minio}. Each can be switched off with
 * {@code management.health.<name>.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformHealthConfiguration {

    /**
     * The {@code database} indicator.
     *
     * @param dataSource the platform data source
     * @param repository the resource repository
     * @return the indicator
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "management.health.database.enabled", matchIfMissing = true)
    public DatabaseHealthIndicator databaseHealthIndicator(final DataSource dataSource,
            final LdpResourceRepository repository) {
        return new DatabaseHealthIndicator(dataSource, repository);
    }

    /**
     * The {@code ocfl} indicator.
     *
     * @param store the storage layer
     * @return the indicator
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "management.health.ocfl.enabled", matchIfMissing = true)
    public OcflHealthIndicator ocflHealthIndicator(final BinaryStore store) {
        return new OcflHealthIndicator(store);
    }

    /**
     * The {@code minio} indicator, present when the storage root lives in S3 compatible storage.
     *
     * @param client     the S3 client
     * @param properties storage settings
     * @return the indicator
     */
    @Bean
    @ConditionalOnProperty(prefix = "articulorum.storage", name = "type", havingValue = "s3", matchIfMissing = true)
    @ConditionalOnBooleanProperty(name = "management.health.minio.enabled", matchIfMissing = true)
    public MinioHealthIndicator minioHealthIndicator(final S3Client client, final StorageProperties properties) {
        return new MinioHealthIndicator(client, properties.getS3().getEndpoint(), properties.getS3().getBucket());
    }

}
