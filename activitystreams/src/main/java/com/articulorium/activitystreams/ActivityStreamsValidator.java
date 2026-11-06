package com.articulorium.activitystreams;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks Activity Streams 2.0 documents against the rules of the core specification and the
 * ranges of the Activity Vocabulary properties.
 *
 * <p>What is checked, on the document and on every embedded object:</p>
 * <ul>
 *   <li>{@code @context} includes the Activity Streams context ({@link #strict()} only: with
 *       {@link #lenient()} it is implied, as for {@code application/activity+json});</li>
 *   <li>the document has a {@code type}; every {@code type} is a string or a list of strings
 *       naming an Activity Vocabulary type, an extension type as a compact or absolute IRI, or
 *       any term when the context adds extension contexts;</li>
 *   <li>{@code id}, {@code href} and IRI values are absolute IRIs;</li>
 *   <li>object properties ({@code actor}, {@code object}, {@code target}, {@code to},
 *       {@code url}, {@code attachment}, ...) hold IRIs, objects or lists of them;</li>
 *   <li>date-times ({@code published}, {@code updated}, {@code startTime}, {@code endTime},
 *       {@code deleted}) are RFC 3339 date-times with a time zone offset, {@code duration} an
 *       {@code xsd:duration};</li>
 *   <li>plain values: strings, language maps, media types, language tags, link relations,
 *       non-negative integers and the numeric ranges of {@code accuracy}, {@code latitude},
 *       {@code longitude}, {@code radius};</li>
 *   <li>links have an {@code href}; intransitive activities have no {@code object}; a
 *       {@code Question} has {@code oneOf} or {@code anyOf}, not both.</li>
 * </ul>
 *
 * <p>Unknown (extension) properties are not checked. Instances are immutable and thread
 * safe.</p>
 */
public final class ActivityStreamsValidator {

    /** Deepest nesting of embedded objects that is checked. */
    public static final int MAX_DEPTH = 32;

    private static final ActivityStreamsValidator STRICT = new ActivityStreamsValidator(true);

    private static final ActivityStreamsValidator LENIENT = new ActivityStreamsValidator(false);

    /** Properties whose values are IRIs, objects or links (or lists of them). */
    private static final Set<String> OBJECT_PROPERTIES = Set.of("actor", "anyOf", "attachment",
        "attributedTo", "audience", "bcc", "bto", "cc", "context", "current", "describes", "first",
        "generator", "icon", "image", "inReplyTo", "instrument", "items", "last", "location",
        "next", "object", "oneOf", "orderedItems", "origin", "partOf", "preview", "prev",
        "relationship", "replies", "result", "subject", "tag", "target", "to", "url");

    /** Properties whose values are RFC 3339 date-times. */
    private static final Set<String> DATE_TIME_PROPERTIES = Set.of("deleted", "endTime",
        "published", "startTime", "updated");

    /** Properties whose values are plain strings. */
    private static final Set<String> STRING_PROPERTIES = Set.of("content", "name", "summary");

    /** Properties whose values are non-negative integers. */
    private static final Set<String> COUNT_PROPERTIES = Set.of("height", "startIndex", "totalItems",
        "width");

    /** Units of {@code radius} and {@code altitude} (or an IRI). */
    private static final Set<String> UNITS = Set.of("cm", "feet", "inches", "km", "m", "miles");

    private static final Pattern DURATION = Pattern.compile(
        "-?P(?=\\d|T\\d)(\\d+Y)?(\\d+M)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?");

    private static final Pattern MEDIA_TYPE = Pattern.compile(
        "[A-Za-z0-9][\\w!#$&^.+-]*/[A-Za-z0-9][\\w!#$&^.+-]*(\\s*;.*)?");

    private static final Pattern LANGUAGE_TAG = Pattern.compile(
        "[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*|und");

    private static final Pattern REL = Pattern.compile("[^\\s,]+");

    private final boolean contextRequired;

    private ActivityStreamsValidator(final boolean contextRequired) {
        this.contextRequired = contextRequired;
    }

    /**
     * Returns the validator for documents that must state the Activity Streams context, such as
     * {@code application/ld+json} documents.
     *
     * @return the strict validator
     */
    public static ActivityStreamsValidator strict() {
        return STRICT;
    }

    /**
     * Returns the validator for documents whose Activity Streams context is implied when not
     * stated, such as {@code application/activity+json} documents.
     *
     * @return the lenient validator
     */
    public static ActivityStreamsValidator lenient() {
        return LENIENT;
    }

    /**
     * Validates a document.
     *
     * @param document the document
     * @return the problems found, in document order; empty when the document is valid
     */
    public List<Violation> validate(final AsObject document) {
        final List<Violation> violations = new ArrayList<>();
        final List<Object> contexts = document.context();
        final boolean statesContext = contexts.contains(ActivityStreams.CONTEXT);
        if (contextRequired && !statesContext) {
            violations.add(new Violation("@context", "must include " + ActivityStreams.CONTEXT));
        }
        for (final Object context : contexts) {
            if (!(context instanceof String) && !(context instanceof Map<?, ?>)) {
                violations.add(new Violation("@context", "must hold IRIs or context objects"));
                break;
            }
        }
        // terms outside the Activity Vocabulary are defined by any further context
        final boolean extended = contexts.stream()
            .anyMatch(context -> !ActivityStreams.CONTEXT.equals(context));
        if (!document.has("type")) {
            violations.add(new Violation("type", "is required"));
        }
        validateObject(document.properties(), "", 0, extended, violations);
        return Collections.unmodifiableList(violations);
    }

    private void validateObject(final Map<String, Object> object, final String path,
            final int depth, final boolean extended, final List<Violation> violations) {
        if (depth > MAX_DEPTH) {
            violations.add(new Violation(path,
                "nests objects deeper than " + MAX_DEPTH + " levels"));
            return;
        }
        final AsObject view = AsObject.wrap(object);
        if (object.containsKey("type")) {
            validateTypes(object.get("type"), at(path, "type"), extended, violations);
        }
        if (object.containsKey("id") && !isIri(object.get("id"))) {
            violations.add(new Violation(at(path, "id"), "must be an absolute IRI"));
        }
        for (final Map.Entry<String, Object> entry : object.entrySet()) {
            validateProperty(entry.getKey(), entry.getValue(), at(path, entry.getKey()), depth,
                extended, violations);
        }
        if (view.isLink() && !object.containsKey("href")) {
            violations.add(new Violation(at(path, "href"), "is required for links"));
        }
        if (view.isKind(AsType.Kind.INTRANSITIVE_ACTIVITY) && !view.isKind(AsType.Kind.ACTIVITY)
                && object.containsKey("object")) {
            violations.add(new Violation(at(path, "object"),
                "must not be given for intransitive activities"));
        }
        if (view.isA(AsType.QUESTION) && object.containsKey("oneOf")
                && object.containsKey("anyOf")) {
            violations.add(new Violation(at(path, "oneOf"),
                "and anyOf must not both be given"));
        }
    }

    private void validateProperty(final String name, final Object value, final String path,
            final int depth, final boolean extended, final List<Violation> violations) {
        if (OBJECT_PROPERTIES.contains(name)) {
            validateReferences(value, path, depth, extended, violations);
        } else if (DATE_TIME_PROPERTIES.contains(name)) {
            if (!(value instanceof String text) || AsObject.parseDateTime(text).isEmpty()) {
                violations.add(new Violation(path,
                    "must be an RFC 3339 date-time with a time zone offset"));
            }
        } else if (STRING_PROPERTIES.contains(name)) {
            if (!(value instanceof String)) {
                violations.add(new Violation(path, "must be a string"));
            }
        } else if (name.endsWith("Map") && STRING_PROPERTIES.contains(name.substring(0,
                name.length() - "Map".length()))) {
            validateLanguageMap(value, path, violations);
        } else if (COUNT_PROPERTIES.contains(name)) {
            if (!isInteger(value) || ((Number) value).longValue() < 0) {
                violations.add(new Violation(path, "must be a non-negative integer"));
            }
        } else {
            validateValue(name, value, path, depth, extended, violations);
        }
    }

    private void validateValue(final String name, final Object value, final String path,
            final int depth, final boolean extended, final List<Violation> violations) {
        switch (name) {
            case "href" -> {
                if (!isIri(value)) {
                    violations.add(new Violation(path, "must be an absolute IRI"));
                }
            }
            case "mediaType" -> {
                if (!(value instanceof String text) || !MEDIA_TYPE.matcher(text).matches()) {
                    violations.add(new Violation(path, "must be a media type"));
                }
            }
            case "hreflang" -> {
                if (!(value instanceof String text) || !LANGUAGE_TAG.matcher(text).matches()) {
                    violations.add(new Violation(path, "must be a BCP 47 language tag"));
                }
            }
            case "rel" -> {
                for (final Object rel : AsObject.list(value)) {
                    if (!(rel instanceof String text) || !REL.matcher(text).matches()) {
                        violations.add(new Violation(path,
                            "must hold link relations without white space or commas"));
                        break;
                    }
                }
            }
            case "duration" -> {
                if (!(value instanceof String text) || !DURATION.matcher(text).matches()) {
                    violations.add(new Violation(path, "must be an xsd:duration such as PT2H30M"));
                }
            }
            case "accuracy" -> range(value, 0, 100, path, violations);
            case "latitude" -> range(value, -90, 90, path, violations);
            case "longitude" -> range(value, -180, 180, path, violations);
            case "radius" -> range(value, 0, Double.MAX_VALUE, path, violations);
            case "altitude" -> range(value, -Double.MAX_VALUE, Double.MAX_VALUE, path, violations);
            case "units" -> {
                if (!(value instanceof String text) || !UNITS.contains(text) && !isIri(text)) {
                    violations.add(new Violation(path, "must be one of " + UNITS + " or an IRI"));
                }
            }
            case "closed" -> {
                if (!(value instanceof Boolean)) {
                    final boolean dateTime = value instanceof String text
                        && AsObject.parseDateTime(text).isPresent();
                    if (!dateTime) {
                        validateReferences(value, path, depth, extended, violations);
                    }
                }
            }
            case "formerType" -> validateTypes(value, path, extended, violations);
            default -> {
                // extension or unconstrained property
            }
        }
    }

    private void validateReferences(final Object value, final String path, final int depth,
            final boolean extended, final List<Violation> violations) {
        final List<Object> values = AsObject.list(value);
        for (int index = 0; index < values.size(); index++) {
            final Object item = values.get(index);
            final String itemPath = value instanceof List<?> ? path + "[" + index + "]" : path;
            if (item instanceof String) {
                if (!isIri(item)) {
                    violations.add(new Violation(itemPath, "must be an absolute IRI"));
                }
            } else if (item instanceof Map<?, ?> map) {
                validateObject(cast(map), itemPath, depth + 1, extended, violations);
            } else {
                violations.add(new Violation(itemPath,
                    "must be an IRI, an object or a list of them"));
            }
        }
    }

    private static void validateTypes(final Object value, final String path, final boolean extended,
            final List<Violation> violations) {
        final List<Object> types = AsObject.list(value);
        if (types.isEmpty()) {
            violations.add(new Violation(path, "must not be empty"));
        }
        for (final Object type : types) {
            if (!(type instanceof String text) || text.isBlank()) {
                violations.add(new Violation(path, "must be a string or a list of strings"));
            } else if (AsType.of(text).isEmpty() && !extended && !isIri(text)) {
                violations.add(new Violation(path, "'" + text + "' is not an Activity Streams type"
                    + " (extension types need a compact or absolute IRI, or a context)"));
            }
        }
    }

    private static void validateLanguageMap(final Object value, final String path,
            final List<Violation> violations) {
        if (!(value instanceof Map<?, ?> map)) {
            violations.add(new Violation(path, "must be an object of language tags to strings"));
            return;
        }
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final boolean tag = entry.getKey() instanceof String key
                && LANGUAGE_TAG.matcher(key).matches();
            if (!tag || !(entry.getValue() instanceof String)) {
                violations.add(new Violation(path,
                    "must be an object of language tags to strings"));
                return;
            }
        }
    }

    private static void range(final Object value, final double min, final double max,
            final String path, final List<Violation> violations) {
        if (!(value instanceof Number number) || number.doubleValue() < min
                || number.doubleValue() > max || Double.isNaN(number.doubleValue())) {
            violations.add(new Violation(path, "must be a number between " + format(min) + " and "
                + format(max)));
        }
    }

    private static String format(final double value) {
        if (Math.abs(value) == Double.MAX_VALUE) {
            return value < 0 ? "-infinity" : "infinity";
        }
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static boolean isInteger(final Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short
            || value instanceof java.math.BigInteger;
    }

    /**
     * Tells whether a value is an absolute IRI (compact IRIs such as {@code as:Public} included).
     *
     * @param value the value
     * @return {@code true} for an absolute IRI string
     */
    static boolean isIri(final Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            return false;
        }
        try {
            return URI.create(text).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String at(final String path, final String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

}
