package com.articulorum.platform.health;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import com.articulorum.platform.repository.LdpResourceRepository;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the platform database is usable for LDP operations.
 *
 * <p>Spring Boot's generic {@code db} indicator only runs a validation query. This indicator
 * additionally checks the platform's own schema: it counts the stored resources and requires the
 * root container to exist (it is created on start-up, so its absence means the schema is broken
 * or start-up failed). Details report the database product and version, the JDBC URL and the
 * number of resources.</p>
 */
public class DatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;

    private final LdpResourceRepository repository;

    /**
     * Creates the indicator.
     *
     * @param dataSource the platform data source
     * @param repository the resource repository
     */
    public DatabaseHealthIndicator(final DataSource dataSource, final LdpResourceRepository repository) {
        super("Platform database health check failed");
        this.dataSource = dataSource;
        this.repository = repository;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            final DatabaseMetaData metadata = connection.getMetaData();
            builder.withDetail("database", metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion())
                .withDetail("url", metadata.getURL());
        }
        final long resources = repository.count();
        final boolean root = repository.existsByPath("");
        builder.withDetail("resources", resources).withDetail("rootContainer", root);
        if (root) {
            builder.up();
        } else {
            builder.down().withDetail("error", "The root container does not exist");
        }
    }

}
