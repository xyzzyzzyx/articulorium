package com.articulorium.activitystreams;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An Activity Streams 2.0 object, activity, actor, collection or link.
 *
 * <p>Activity Streams is open: any object may carry extension properties and several values
 * per property, and most properties take an IRI, an embedded object or a list of both. So an
 * {@code AsObject} keeps the document's JSON as is (an ordered map of strings, numbers,
 * booleans, {@code null}s, lists and maps) and offers typed accessors for the common
 * properties on top. Instances are immutable; {@link #builder()} and {@link #toBuilder()}
 * create new ones.</p>
 *
 * <p>Use {@link ActivityStreamsJson} to read and write documents and
 * {@link ActivityStreamsValidator} to check them.</p>
 */
public final class AsObject {

    private final Map<String, Object> properties;

    private AsObject(final Map<String, Object> properties) {
        this.properties = properties;
    }

    /**
     * Creates an object from its JSON properties.
     *
     * @param properties the properties: JSON values (strings, numbers, booleans, {@code null},
     *                   lists and maps with string keys); {@link AsObject}s, {@link AsType}s,
     *                   {@link URI}s and date-times are converted to JSON
     * @return the object, holding a deep, unmodifiable copy of the properties
     * @throws IllegalArgumentException when a value cannot be represented in JSON
     */
    public static AsObject of(final Map<String, ?> properties) {
        Objects.requireNonNull(properties, "properties");
        return new AsObject(copyMap(properties));
    }

    /**
     * Wraps properties that are already an unmodifiable JSON copy, without copying them again.
     *
     * @param properties JSON properties
     * @return the object
     */
    static AsObject wrap(final Map<String, Object> properties) {
        return new AsObject(properties);
    }

    /**
     * Creates a reference: an object with only an {@code id}.
     *
     * @param id the IRI of the object
     * @return the object
     */
    public static AsObject reference(final String id) {
        return builder().id(id).build();
    }

    /**
     * Starts a new object.
     *
     * @param types its types
     * @return a builder
     */
    public static Builder builder(final AsType... types) {
        final Builder builder = new Builder(new LinkedHashMap<>());
        if (types.length > 0) {
            builder.type(types);
        }
        return builder;
    }

    /**
     * Starts a modified copy of this object.
     *
     * @return a builder holding this object's properties
     */
    public Builder toBuilder() {
        return new Builder(new LinkedHashMap<>(properties));
    }

    /**
     * Returns every property, in document order.
     *
     * @return an unmodifiable map of JSON values
     */
    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * Returns the value of a property.
     *
     * @param name the property name, for example {@code summary}
     * @return the JSON value, or empty when the property is absent or {@code null}
     */
    public Optional<Object> get(final String name) {
        return Optional.ofNullable(properties.get(name));
    }

    /**
     * Tells whether the object has a property.
     *
     * @param name the property name
     * @return {@code true} when present, even with a {@code null} value
     */
    public boolean has(final String name) {
        return properties.containsKey(name);
    }

    /**
     * Returns the {@code @context}.
     *
     * @return the context entries (strings or maps), empty when not stated
     */
    public List<Object> context() {
        return list(properties.get("@context"));
    }

    /**
     * Returns the {@code id}.
     *
     * @return the IRI of the object, or empty for an anonymous object
     */
    public Optional<String> id() {
        return string("id");
    }

    /**
     * Returns the {@code type} values, Activity Vocabulary or not.
     *
     * @return the type strings, in document order
     */
    public List<String> types() {
        return strings("type");
    }

    /**
     * Returns the first Activity Vocabulary type of the object.
     *
     * @return the type, or empty when no type is from the Activity Vocabulary
     */
    public Optional<AsType> type() {
        return types().stream().map(AsType::of).flatMap(Optional::stream).findFirst();
    }

    /**
     * Tells whether the object has a type.
     *
     * @param type the type
     * @return {@code true} when one of the types is {@code type}
     */
    public boolean isA(final AsType type) {
        return types().stream().map(AsType::of).anyMatch(found -> found.equals(Optional.of(type)));
    }

    /**
     * Tells whether one of the types is of a kind.
     *
     * @param kind the kind
     * @return {@code true} when a type of that kind is present
     */
    public boolean isKind(final AsType.Kind kind) {
        return types().stream().map(AsType::of).flatMap(Optional::stream)
            .anyMatch(type -> type.kind() == kind);
    }

    /**
     * Tells whether the object is an activity (transitive or intransitive).
     *
     * @return {@code true} for activities
     */
    public boolean isActivity() {
        return isKind(AsType.Kind.ACTIVITY) || isKind(AsType.Kind.INTRANSITIVE_ACTIVITY);
    }

    /**
     * Tells whether the object is a link.
     *
     * @return {@code true} for {@code Link} and {@code Mention}
     */
    public boolean isLink() {
        return isKind(AsType.Kind.LINK);
    }

    /**
     * Returns the {@code actor}s of an activity.
     *
     * @return the actors; references (IRIs) are objects with only an {@code id}
     */
    public List<AsObject> actor() {
        return objects("actor");
    }

    /**
     * Returns the {@code object}s of an activity.
     *
     * @return the objects; references (IRIs) are objects with only an {@code id}
     */
    public List<AsObject> object() {
        return objects("object");
    }

    /**
     * Returns the {@code target}s of an activity.
     *
     * @return the targets; references (IRIs) are objects with only an {@code id}
     */
    public List<AsObject> target() {
        return objects("target");
    }

    /**
     * Returns the {@code url}s of the object.
     *
     * @return the IRIs: plain IRIs as given, the {@code href} of links
     */
    public List<String> url() {
        return references("url");
    }

    /**
     * Returns when the object was {@code published}.
     *
     * @return the date-time, or empty when absent or not a valid date-time
     */
    public Optional<OffsetDateTime> published() {
        return dateTime("published");
    }

    /**
     * Returns when the object was last {@code updated}.
     *
     * @return the date-time, or empty when absent or not a valid date-time
     */
    public Optional<OffsetDateTime> updated() {
        return dateTime("updated");
    }

    /**
     * Returns the plain {@code name}.
     *
     * @return the name, or empty (language maps, {@code nameMap}, are read with {@link #get})
     */
    public Optional<String> name() {
        return string("name");
    }

    /**
     * Returns the plain {@code summary}.
     *
     * @return the summary, or empty
     */
    public Optional<String> summary() {
        return string("summary");
    }

    /**
     * Returns the plain {@code content}.
     *
     * @return the content, or empty
     */
    public Optional<String> content() {
        return string("content");
    }

    /**
     * Returns the {@code href} of a link.
     *
     * @return the IRI, or empty
     */
    public Optional<String> href() {
        return string("href");
    }

    /**
     * Returns a property that holds a string.
     *
     * @param name the property name
     * @return the string, or empty when absent or not a string
     */
    public Optional<String> string(final String name) {
        return properties.get(name) instanceof String text ? Optional.of(text) : Optional.empty();
    }

    /**
     * Returns the strings of a property holding a string or a list of strings.
     *
     * @param name the property name
     * @return the strings, in order; other values are left out
     */
    public List<String> strings(final String name) {
        final List<String> result = new ArrayList<>();
        for (final Object value : list(properties.get(name))) {
            if (value instanceof String text) {
                result.add(text);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns a property that holds a date-time.
     *
     * @param name the property name, for example {@code startTime}
     * @return the date-time, or empty when absent or not an RFC 3339 date-time
     */
    public Optional<OffsetDateTime> dateTime(final String name) {
        return string(name).flatMap(AsObject::parseDateTime);
    }

    /**
     * Returns the objects of a property holding IRIs, objects or a list of both.
     *
     * @param name the property name
     * @return the embedded objects; IRIs become references (objects with only an {@code id})
     */
    public List<AsObject> objects(final String name) {
        final List<AsObject> result = new ArrayList<>();
        for (final Object value : list(properties.get(name))) {
            if (value instanceof String iri) {
                result.add(reference(iri));
            } else if (value instanceof Map<?, ?> map) {
                result.add(new AsObject(castMap(map)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the IRIs a property refers to.
     *
     * @param name the property name
     * @return plain IRIs as given, the {@code href} of embedded links and the {@code id} of other
     *         embedded objects (anonymous objects are left out)
     */
    public List<String> references(final String name) {
        final List<String> result = new ArrayList<>();
        for (final Object value : list(properties.get(name))) {
            if (value instanceof String iri) {
                result.add(iri);
            } else if (value instanceof Map<?, ?> map) {
                final AsObject embedded = new AsObject(castMap(map));
                final Optional<String> iri = embedded.isLink() || embedded.id().isEmpty()
                    ? embedded.href().or(embedded::id) : embedded.id();
                iri.ifPresent(result::add);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof AsObject object && properties.equals(object.properties);
    }

    @Override
    public int hashCode() {
        return properties.hashCode();
    }

    @Override
    public String toString() {
        return "AsObject" + properties;
    }

    /**
     * Parses an RFC 3339 date-time (with a time zone offset, as Activity Streams requires).
     *
     * @param value the text
     * @return the date-time, or empty when the text is not one
     */
    static Optional<OffsetDateTime> parseDateTime(final String value) {
        try {
            return Optional.of(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Formats a date-time as Activity Streams writes it: UTC, to the millisecond.
     *
     * @param instant the instant
     * @return for example {@code 2026-11-05T17:32:00Z}
     */
    static String formatDateTime(final Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Reads a JSON value as a list: a list as is, {@code null} as empty, anything else as a
     * list of one.
     *
     * @param value the value
     * @return the values
     */
    static List<Object> list(final Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values) {
            return Collections.unmodifiableList(values);
        }
        return List.of(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> copyMap(final Map<?, ?> map) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                    "Property names must be strings: " + entry.getKey());
            }
            copy.put(key, toJson(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object toJson(final Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof AsObject object) {
            return object.properties;
        }
        if (value instanceof AsType type) {
            return type.term();
        }
        if (value instanceof URI uri) {
            return uri.toString();
        }
        if (value instanceof Instant instant) {
            return formatDateTime(instant);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.getOffset().equals(ZoneOffset.UTC)
                ? formatDateTime(dateTime.toInstant()) : dateTime.toString();
        }
        if (value instanceof ZonedDateTime dateTime) {
            return toJson(dateTime.toOffsetDateTime());
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof Collection<?> values) {
            final List<Object> copy = new ArrayList<>(values.size());
            for (final Object item : values) {
                copy.add(toJson(item));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
    }

    /**
     * Builds {@link AsObject}s. Setting a property to {@code null} removes it.
     */
    public static final class Builder {

        private final Map<String, Object> properties;

        private Builder(final Map<String, Object> properties) {
            this.properties = properties;
        }

        /**
         * Sets a property.
         *
         * @param name  the property name
         * @param value a JSON value, an {@link AsObject}, an {@link AsType}, a {@link URI}, a
         *              date-time or a collection of those; {@code null} removes the property
         * @return this builder
         * @throws IllegalArgumentException when the value cannot be represented in JSON
         */
        public Builder property(final String name, final Object value) {
            Objects.requireNonNull(name, "name");
            if (value == null) {
                properties.remove(name);
            } else {
                properties.put(name, toJson(value));
            }
            return this;
        }

        /**
         * Sets the {@code @context}.
         *
         * @param contexts the context entries; the Activity Streams context should be first
         * @return this builder
         */
        public Builder context(final Object... contexts) {
            return property("@context", contexts.length == 1 ? contexts[0] : List.of(contexts));
        }

        /**
         * Sets the {@code id}.
         *
         * @param id an absolute IRI
         * @return this builder
         */
        public Builder id(final String id) {
            return property("id", id);
        }

        /**
         * Sets the {@code type}: one type is written as a string, several as a list.
         *
         * @param types Activity Vocabulary types, extension type strings (compact or full IRIs)
         * @return this builder
         */
        public Builder type(final Object... types) {
            return property("type", types.length == 1 ? types[0] : List.of(types));
        }

        /**
         * Sets the {@code actor}.
         *
         * @param actor an IRI, an {@link AsObject} or a list of them
         * @return this builder
         */
        public Builder actor(final Object actor) {
            return property("actor", actor);
        }

        /**
         * Sets the {@code object}.
         *
         * @param object an IRI, an {@link AsObject} or a list of them
         * @return this builder
         */
        public Builder object(final Object object) {
            return property("object", object);
        }

        /**
         * Sets the {@code target}.
         *
         * @param target an IRI, an {@link AsObject} or a list of them
         * @return this builder
         */
        public Builder target(final Object target) {
            return property("target", target);
        }

        /**
         * Sets the {@code url}.
         *
         * @param url an IRI, a {@code Link} or a list of them
         * @return this builder
         */
        public Builder url(final Object url) {
            return property("url", url);
        }

        /**
         * Sets when the object was {@code published}.
         *
         * @param published the instant
         * @return this builder
         */
        public Builder published(final Instant published) {
            return property("published", published);
        }

        /**
         * Sets when the object was last {@code updated}.
         *
         * @param updated the instant
         * @return this builder
         */
        public Builder updated(final Instant updated) {
            return property("updated", updated);
        }

        /**
         * Sets the plain {@code name}.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(final String name) {
            return property("name", name);
        }

        /**
         * Sets the plain {@code summary}.
         *
         * @param summary the summary (HTML allowed)
         * @return this builder
         */
        public Builder summary(final String summary) {
            return property("summary", summary);
        }

        /**
         * Sets the plain {@code content}.
         *
         * @param content the content (HTML allowed)
         * @return this builder
         */
        public Builder content(final String content) {
            return property("content", content);
        }

        /**
         * Builds the object.
         *
         * @return a new, immutable object
         */
        public AsObject build() {
            return AsObject.of(properties);
        }

    }

}
