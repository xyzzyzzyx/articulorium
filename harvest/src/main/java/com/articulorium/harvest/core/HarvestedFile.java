package com.articulorium.harvest.core;

import java.net.URI;
import java.util.Objects;

/**
 * A file (bitstream, binary) of a harvested record, referenced where the source keeps it.
 *
 * @param location  where the source serves the file's bytes
 * @param name      the file name, may be {@code null}
 * @param mediaType the media type, may be {@code null}
 * @param size      the size in bytes, may be {@code null}
 */
public record HarvestedFile(URI location, String name, String mediaType, Long size) {

    /**
     * Creates a file reference.
     *
     * @param location  the location (absolute)
     * @param name      the name
     * @param mediaType the media type
     * @param size      the size
     */
    public HarvestedFile {
        Objects.requireNonNull(location, "location");
        if (!location.isAbsolute()) {
            throw new IllegalArgumentException("The location of a harvested file must be absolute: " + location);
        }
    }

}
