package com.articulorum.topology.model;

import java.util.List;

/**
 * A kind of file a topology node holds, for example the original image of a page.
 *
 * @param use        the PCDM use IRI, for example {@code http://pcdm.org/use#OriginalFile}
 * @param label      the label of the file kind
 * @param mediaTypes media types expected for it (informative), may be empty
 */
public record FileUse(String use, String label, List<String> mediaTypes) {

    /**
     * Creates a file use with an immutable media type list.
     *
     * @param use        the PCDM use IRI
     * @param label      the label
     * @param mediaTypes the media types
     */
    public FileUse {
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }

}
