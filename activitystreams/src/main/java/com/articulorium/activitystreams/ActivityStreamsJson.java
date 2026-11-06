package com.articulorium.activitystreams;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes Activity Streams 2.0 documents: JSON (compacted JSON-LD) as served with
 * {@code application/activity+json} or {@code application/ld+json}.
 *
 * <p>Reading is strict about JSON: the document must be one JSON object, with no duplicate
 * property names and nothing after it. Reading does not validate Activity Streams rules; pass
 * the result to {@link ActivityStreamsValidator} (or use {@link #readValid}).</p>
 *
 * <p>Writing is deterministic: {@code @context}, {@code id} and {@code type} come first, the
 * other properties keep their order, and a document without {@code @context} gets the Activity
 * Streams context. Reading what was written gives an equal object, and writing it again the same
 * bytes.</p>
 */
public final class ActivityStreamsJson {

    private final JsonMapper mapper;

    private final ActivityStreamsValidator validator;

    /**
     * Creates a reader and writer with its own JSON mapper.
     */
    public ActivityStreamsJson() {
        this(JsonMapper.builder().build());
    }

    /**
     * Creates a reader and writer from an application's JSON mapper.
     *
     * @param mapper the mapper to start from; strict duplicate detection and trailing token
     *               checks are turned on in a copy, the given mapper is not changed
     */
    public ActivityStreamsJson(final JsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        this.mapper = mapper.rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        this.validator = ActivityStreamsValidator.lenient();
    }

    /**
     * Reads a document.
     *
     * @param json the document, UTF-8 encoded
     * @return the object
     * @throws ActivityStreamsException when the document is not a JSON object
     */
    public AsObject read(final byte[] json) {
        Objects.requireNonNull(json, "json");
        final Object value;
        try {
            value = mapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new ActivityStreamsException("is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ActivityStreamsException("must be a JSON object", null);
        }
        return AsObject.of(cast(map));
    }

    /**
     * Reads a document.
     *
     * @param json the document
     * @return the object
     * @throws ActivityStreamsException when the document is not a JSON object
     */
    public AsObject read(final String json) {
        Objects.requireNonNull(json, "json");
        return read(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads and validates a document.
     *
     * @param json      the document, UTF-8 encoded
     * @param mediaType the media type it came with: for {@code application/activity+json} the
     *                  Activity Streams context is implied, for any other the document must state
     *                  it; {@code null} counts as another media type
     * @return the object; with the Activity Streams context added when it was implied
     * @throws ActivityStreamsException when the document is not JSON or not valid
     */
    public AsObject readValid(final byte[] json, final String mediaType) {
        final AsObject object = read(json);
        final boolean implied = ActivityStreams.isActivityJson(mediaType);
        final List<Violation> violations = implied ? validator.validate(object)
            : ActivityStreamsValidator.strict().validate(object);
        if (!violations.isEmpty()) {
            throw new ActivityStreamsException(violations);
        }
        return implied ? withContext(object) : object;
    }

    /**
     * Writes a document.
     *
     * @param object the object
     * @return the document, UTF-8 encoded
     */
    public byte[] write(final AsObject object) {
        Objects.requireNonNull(object, "object");
        try {
            return mapper.writeValueAsBytes(ordered(withContext(object)));
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to write the Activity Streams document", e);
        }
    }

    /**
     * Writes a document.
     *
     * @param object the object
     * @return the document
     */
    public String writeString(final AsObject object) {
        return new String(write(object), StandardCharsets.UTF_8);
    }

    /**
     * Adds the Activity Streams context to a document that does not state a context.
     *
     * @param object the object
     * @return the object, with {@code @context} set
     */
    public static AsObject withContext(final AsObject object) {
        if (object.has("@context")) {
            return object;
        }
        return object.toBuilder().context(ActivityStreams.CONTEXT).build();
    }

    private static Map<String, Object> ordered(final AsObject object) {
        final Map<String, Object> properties = object.properties();
        final Map<String, Object> ordered = new LinkedHashMap<>();
        for (final String first : List.of("@context", "id", "type")) {
            if (properties.containsKey(first)) {
                ordered.put(first, properties.get(first));
            }
        }
        properties.forEach(ordered::putIfAbsent);
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

}
