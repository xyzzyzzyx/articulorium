package com.articulorium.discovery.iiif;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * What the IIIF export of a resource presents.
 *
 * @param id        the public URL of the Manifest or Collection
 * @param type      {@code Manifest} or {@code Collection}
 * @param thumbnail the URL of its thumbnail image, {@code null} when it has none
 * @param canvases  the number of canvases of a Manifest
 * @param images    canvases painted with an image
 * @param videos    canvases painted with a video
 * @param sounds    canvases painted with audio
 * @param documents PDF documents, painted or offered as renderings
 * @param members   the number of members of a Collection
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IiifSummary(String id, String type, @Nullable String thumbnail, int canvases, int images,
        int videos, int sounds, int documents, int members) {

    /**
     * Tells whether the viewer has something to present.
     *
     * @return {@code true} for Manifests with painted media or documents, and for non-empty
     *     Collections
     */
    public boolean viewable() {
        return "Collection".equals(type) ? members > 0 : images + videos + sounds + documents > 0;
    }

}
