package com.articulorium.ai.media;

import java.util.List;

/**
 * Suggested metadata for a binary. Nothing is saved: the caller keeps the statements it wants and
 * adds them to the description with a SPARQL Update.
 *
 * @param path        the binary's path
 * @param description the path of its description, where the statements go
 * @param mediaType   its media type
 * @param kind        image, video or PDF
 * @param imageShown  whether the model saw the image itself
 * @param statements  the suggested statements (those the description already has are left out)
 * @param notes       what limited the suggestion: {@code IMAGE_FORMAT_NOT_SHOWN},
 *                    {@code IMAGE_TOO_LARGE_TO_SHOW}, {@code IMAGE_NOT_SHOWN}, {@code VIDEO_NOT_SHOWN},
 *                    {@code FILE_NOT_READ}, {@code TEXT_FROM_FIRST_PAGES}, {@code TEXT_SHORTENED},
 *                    {@code NO_TEXT}, {@code TEXT_NOT_EXTRACTABLE}, {@code ENCRYPTED},
 *                    {@code UNREADABLE_METADATA}
 */
public record MediaMetadataSuggestion(String path, String description, String mediaType, MediaKind kind,
        boolean imageShown, List<MetadataStatement> statements, List<String> notes) {
}
