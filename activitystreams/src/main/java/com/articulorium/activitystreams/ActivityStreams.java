package com.articulorium.activitystreams;

import java.util.Locale;

/**
 * Constants of the W3C Activity Streams 2.0 specifications.
 *
 * @see <a href="https://www.w3.org/TR/activitystreams-core/">Activity Streams 2.0</a>
 * @see <a href="https://www.w3.org/TR/activitystreams-vocabulary/">Activity Vocabulary</a>
 */
public final class ActivityStreams {

    /** The normative JSON-LD {@code @context} of Activity Streams 2.0. */
    public static final String CONTEXT = "https://www.w3.org/ns/activitystreams";

    /** The namespace of the Activity Vocabulary terms ({@code as:}). */
    public static final String NAMESPACE = CONTEXT + "#";

    /** The compact prefix of the namespace. */
    public static final String PREFIX = "as:";

    /** The special collection addressing everyone ({@code as:Public}). */
    public static final String PUBLIC = NAMESPACE + "Public";

    /** The Activity Streams media type. */
    public static final String MEDIA_TYPE = "application/activity+json";

    /** The JSON-LD media type, which Activity Streams documents may also be served with. */
    public static final String JSON_LD_MEDIA_TYPE = "application/ld+json";

    /** The JSON-LD media type with the Activity Streams profile. */
    public static final String JSON_LD_PROFILE_MEDIA_TYPE =
        JSON_LD_MEDIA_TYPE + "; profile=\"" + CONTEXT + "\"";

    private ActivityStreams() {
        // constants
    }

    /**
     * Tells whether a media type is {@code application/activity+json}, parameters ignored.
     *
     * <p>Documents of this media type imply the Activity Streams {@code @context} when they do
     * not state it (Activity Streams 2.0, section 2.1).</p>
     *
     * @param mediaType a media type, possibly with parameters; may be {@code null}
     * @return {@code true} for {@code application/activity+json}
     */
    public static boolean isActivityJson(final String mediaType) {
        return MEDIA_TYPE.equals(essence(mediaType));
    }

    /**
     * Tells whether a media type can carry an Activity Streams document:
     * {@code application/activity+json}, {@code application/ld+json} or plain
     * {@code application/json}, parameters ignored.
     *
     * @param mediaType a media type, possibly with parameters; may be {@code null}
     * @return {@code true} for a JSON media type Activity Streams documents use
     */
    public static boolean isSupportedMediaType(final String mediaType) {
        final String essence = essence(mediaType);
        return MEDIA_TYPE.equals(essence) || JSON_LD_MEDIA_TYPE.equals(essence)
            || "application/json".equals(essence);
    }

    private static String essence(final String mediaType) {
        if (mediaType == null) {
            return "";
        }
        final int parameters = mediaType.indexOf(';');
        final String essence = parameters < 0 ? mediaType : mediaType.substring(0, parameters);
        return essence.trim().toLowerCase(Locale.ROOT);
    }

}
