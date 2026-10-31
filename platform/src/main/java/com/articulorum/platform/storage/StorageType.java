package com.articulorum.platform.storage;

/**
 * Where the OCFL storage root lives.
 */
public enum StorageType {

    /** The storage root is a directory of the local file system. */
    FILESYSTEM,

    /** The storage root is a bucket (and optional key prefix) of an S3 compatible object store such as MinIO. */
    S3

}
