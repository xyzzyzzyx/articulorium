package com.articulorum.platform.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the OCFL storage layer ({@code articulorum.storage.*}).
 *
 * <p>{@link #getType() type} chooses where the OCFL storage root lives: a local directory
 * ({@code filesystem.root}) or an S3 bucket ({@code s3.*}, MinIO in the docker compose stack).
 * {@code ocfl.*} tunes the OCFL repository itself.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.storage")
public class StorageProperties {

    /** Where the OCFL storage root lives. */
    private StorageType type = StorageType.S3;

    /** Largest binary accepted in a raw (non-multipart) request body. */
    private DataSize maxUploadSize = DataSize.ofGigabytes(2);

    /** Directory used to spool uploads before they are stored; defaults to {@code java.io.tmpdir}. */
    private Path tempDirectory;

    /** File system store settings. */
    private FileSystem filesystem = new FileSystem();

    /** S3 store settings. */
    private S3 s3 = new S3();

    /** OCFL repository settings. */
    private Ocfl ocfl = new Ocfl();

    /**
     * File system store settings ({@code articulorum.storage.filesystem.*}).
     */
    @Getter
    @Setter
    public static class FileSystem {

        /** The OCFL storage root directory; created and initialised when missing. */
        private Path root = Path.of("data", "ocfl");

    }

    /**
     * S3 store settings ({@code articulorum.storage.s3.*}).
     */
    @Getter
    @Setter
    public static class S3 {

        /** Endpoint of an S3 compatible service, e.g. {@code http://minio:9000}; empty for AWS. */
        private String endpoint;

        /** Signing region. */
        private String region = "us-east-1";

        /** Bucket holding the OCFL storage root. */
        private String bucket = "articulorum";

        /** Access key; when empty the AWS default credentials chain is used. */
        private String accessKey;

        /** Secret key matching {@link #accessKey}. */
        private String secretKey;

        /** Use path-style addressing ({@code endpoint/bucket/key}), required by MinIO. */
        private boolean pathStyle = true;

        /** Create the bucket on start-up when it does not exist. */
        private boolean createBucket = true;

        /** Key prefix under which the OCFL storage root lives inside the bucket. */
        private String keyPrefix = "ocfl";

    }

    /**
     * OCFL repository settings ({@code articulorum.storage.ocfl.*}).
     */
    @Getter
    @Setter
    public static class Ocfl {

        /**
         * Directory where new versions are assembled before they are moved into the storage root.
         * It must not be inside the storage root; defaults to a directory below
         * {@code java.io.tmpdir}.
         */
        private Path workDirectory;

        /** Coordinate concurrent writers through a lock table in the platform database. */
        private boolean databaseLock = true;

        /** Name of the lock table used when {@link #databaseLock} is enabled. */
        private String lockTable = "ocfl_object_lock";

    }

}
