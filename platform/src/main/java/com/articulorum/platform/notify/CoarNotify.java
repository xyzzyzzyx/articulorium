package com.articulorum.platform.notify;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validation and classification of COAR Notify notifications.
 *
 * <p>A COAR Notify notification is an Activity Streams 2.0 activity in JSON-LD whose
 * {@code @context} includes both the Activity Streams and the COAR Notify contexts. It has an
 * {@code id} (a URI, usually {@code urn:uuid:...}), one or more {@code type}s (one Activity
 * Streams activity type, optionally with a COAR Notify type such as
 * {@code coar-notify:ReviewAction}), an {@code origin} and a {@code target} (services with an
 * {@code id}, an {@code inbox} and the type {@code Service}) and an {@code object} with an
 * {@code id}.</p>
 *
 * @see <a href="https://coar-notify.net/specification/1.0.1/">COAR Notify 1.0.1</a>
 */
public final class CoarNotify {

    /** The Activity Streams 2.0 JSON-LD context. */
    public static final String AS_CONTEXT = "https://www.w3.org/ns/activitystreams";

    /** The COAR Notify JSON-LD context. */
    public static final String NOTIFY_CONTEXT = "https://coar-repositories.org/notify";

    /** Media type of notifications. */
    public static final String JSON_LD = "application/ld+json";

    /** Activity Streams activity types COAR Notify uses. */
    public static final Set<String> ACTIVITY_TYPES = Set.of("Accept", "Announce", "Flag", "Offer", "Reject",
        "TentativeAccept", "TentativeReject", "Undo");

    /** COAR Notify activity object types (without prefix), mapped to pattern names. */
    public static final Map<String, String> NOTIFY_TYPES = Map.of(
        "ReviewAction", "Review",
        "EndorsementAction", "Endorsement",
        "IngestAction", "Ingest",
        "RelationshipAction", "Relationship",
        "UnprocessableNotification", "Unprocessable Notification");

    private CoarNotify() {
        // utility class
    }

    /**
     * Validates a notification.
     *
     * @param notification the parsed JSON-LD object
     * @return the problems found; empty when the notification is valid
     */
    public static List<String> validate(final Map<String, Object> notification) {
        final List<String> errors = new ArrayList<>();
        final Set<String> contexts = strings(notification.get("@context"));
        if (!contexts.contains(AS_CONTEXT)) {
            errors.add("@context must include " + AS_CONTEXT);
        }
        if (!contexts.contains(NOTIFY_CONTEXT)) {
            errors.add("@context must include " + NOTIFY_CONTEXT);
        }
        if (!isUri(notification.get("id"))) {
            errors.add("id must be a URI, for example urn:uuid:<uuid>");
        }
        final Set<String> types = strings(notification.get("type"));
        if (types.stream().noneMatch(ACTIVITY_TYPES::contains)) {
            errors.add("type must include one of " + ACTIVITY_TYPES);
        }
        validateService(notification.get("origin"), "origin", errors);
        validateService(notification.get("target"), "target", errors);
        if (!(notification.get("object") instanceof Map<?, ?> object) || !isUri(object.get("id"))) {
            errors.add("object must be an object with a URI id");
        }
        return errors;
    }

    private static void validateService(final Object value, final String name, final List<String> errors) {
        if (!(value instanceof Map<?, ?> service)) {
            errors.add(name + " must be an object with id, inbox and type Service");
            return;
        }
        if (!isUri(service.get("id"))) {
            errors.add(name + ".id must be a URI");
        }
        if (!isUri(service.get("inbox"))) {
            errors.add(name + ".inbox must be a URI");
        }
        if (!strings(service.get("type")).contains("Service")) {
            errors.add(name + ".type must be Service");
        }
    }

    /**
     * Names the COAR Notify pattern of a notification, for example {@code Offer Review} or
     * {@code Announce Endorsement}.
     *
     * @param types the notification's types
     * @return the pattern, or the activity type alone when no COAR Notify type is given
     */
    public static String pattern(final Collection<String> types) {
        final Optional<String> activity = types.stream().filter(ACTIVITY_TYPES::contains).findFirst();
        final Optional<String> action = types.stream()
            .map(type -> type.startsWith("coar-notify:") ? type.substring("coar-notify:".length()) : type)
            .filter(NOTIFY_TYPES::containsKey)
            .map(NOTIFY_TYPES::get)
            .findFirst();
        return (activity.orElse("") + action.map(name -> " " + name).orElse("")).trim();
    }

    /**
     * Reads a JSON value that is a string or a list of strings.
     *
     * @param value the value
     * @return the strings, in order
     */
    public static Set<String> strings(final Object value) {
        final Set<String> result = new LinkedHashSet<>();
        if (value instanceof String text) {
            result.add(text);
        } else if (value instanceof Collection<?> list) {
            for (final Object item : list) {
                if (item instanceof String text) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    /**
     * Returns the {@code id} of a JSON object value.
     *
     * @param value an object with an id, or a plain URI string
     * @return the id, or {@code null}
     */
    public static String idOf(final Object value) {
        if (value instanceof Map<?, ?> map && map.get("id") instanceof String id) {
            return id;
        }
        return value instanceof String text ? text : null;
    }

    /**
     * Tells whether a value is an absolute URI.
     *
     * @param value the value
     * @return {@code true} for an absolute URI string
     */
    static boolean isUri(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        try {
            return URI.create(text.trim()).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
