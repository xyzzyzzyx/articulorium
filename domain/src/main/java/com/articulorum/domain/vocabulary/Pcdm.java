package com.articulorum.domain.vocabulary;

/**
 * Terms of the Portland Common Data Model ({@code pcdm:}) and its use extension
 * ({@code pcdmuse:}).
 *
 * @see <a href="https://pcdm.org/2016/04/18/models">PCDM models</a>
 */
public final class Pcdm {

    /** The PCDM namespace. */
    public static final String NAMESPACE = "http://pcdm.org/models#";

    /** A group of objects. */
    public static final String COLLECTION = NAMESPACE + "Collection";

    /** An intellectual entity. */
    public static final String OBJECT = NAMESPACE + "Object";

    /** A sequence of bytes belonging to an object. */
    public static final String FILE = NAMESPACE + "File";

    /** Links a collection or object to a member object or collection. */
    public static final String HAS_MEMBER = NAMESPACE + "hasMember";

    /** Inverse of {@link #HAS_MEMBER}. */
    public static final String MEMBER_OF = NAMESPACE + "memberOf";

    /** Links an object to one of its files. */
    public static final String HAS_FILE = NAMESPACE + "hasFile";

    /** Inverse of {@link #HAS_FILE}. */
    public static final String FILE_OF = NAMESPACE + "fileOf";

    /** Links a collection to a related object it does not contain. */
    public static final String HAS_RELATED_OBJECT = NAMESPACE + "hasRelatedObject";

    /** The PCDM use extension namespace. */
    public static final String USE_NAMESPACE = "http://pcdm.org/use#";

    /** The original file, as received. */
    public static final String ORIGINAL_FILE = USE_NAMESPACE + "OriginalFile";

    /** A file derived for presentation. */
    public static final String SERVICE_FILE = USE_NAMESPACE + "ServiceFile";

    /** A small preview image. */
    public static final String THUMBNAIL_IMAGE = USE_NAMESPACE + "ThumbnailImage";

    /** Text extracted from an image (for example by OCR). */
    public static final String EXTRACTED_TEXT = USE_NAMESPACE + "ExtractedText";

    /** A transcription made by a person. */
    public static final String TRANSCRIPT = USE_NAMESPACE + "Transcript";

    private Pcdm() {
        // constants holder
    }

}
