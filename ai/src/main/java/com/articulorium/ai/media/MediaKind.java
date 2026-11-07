package com.articulorium.ai.media;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The kinds of binaries the agent describes.
 */
public enum MediaKind {

    /** Images (JPEG, PNG, GIF, WebP, TIFF, BMP, HEIF ...). */
    IMAGE,

    /** Videos (MP4, QuickTime, AVI, WebM ...). */
    VIDEO,

    /** PDF documents. */
    PDF;

    /** Image types vision models commonly accept; other images are described from their metadata. */
    static final Set<String> VISION_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /**
     * Tells the kind of a media type.
     *
     * @param mediaType the media type, may carry parameters
     * @return the kind, or empty for unsupported types
     */
    public static Optional<MediaKind> of(final String mediaType) {
        if (mediaType == null) {
            return Optional.empty();
        }
        final String type = essence(mediaType);
        if (type.startsWith("image/")) {
            return Optional.of(IMAGE);
        }
        if (type.startsWith("video/")) {
            return Optional.of(VIDEO);
        }
        if ("application/pdf".equals(type)) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    /**
     * Returns a media type without parameters, in lower case.
     *
     * @param mediaType the media type
     * @return for example {@code image/jpeg}
     */
    public static String essence(final String mediaType) {
        final int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

}
