package com.articulorium.platform.notify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.articulorium.activitystreams.ActivityStreams;
import com.articulorium.activitystreams.ActivityStreamsValidator;
import com.articulorium.activitystreams.AsObject;
import com.articulorium.activitystreams.AsType;
import com.articulorium.activitystreams.Violation;

/**
 * Validation and classification of COAR Notify notifications.
 *
 * <p>A COAR Notify notification is an Activity Streams 2.0 activity in JSON-LD whose
 * {@code @context} includes both the Activity Streams and the COAR Notify contexts. It has an
 * {@code id} (a URI, usually {@code urn:uuid:...}), one or more {@code type}s (one Activity
 * Streams activity type, optionally with a COAR Notify type such as
 * {@code coar-notify:ReviewAction}), an {@code origin} and a {@code target} (services with an
 * {@code id}, an {@code inbox} and the type {@code Service}) and an {@code object} with an
 * {@code id}. Notifications must also be valid Activity Streams 2.0
 * ({@link ActivityStreamsValidator}).</p>
 *
 * @see <a href="https://coar-notify.net/specification/1.0.1/">COAR Notify 1.0.1</a>
 */
public final class CoarNotify {

    /** The Activity Streams 2.0 JSON-LD context. */
    public static final String AS_CONTEXT = ActivityStreams.CONTEXT;

    /** The COAR Notify JSON-LD context. */
    public static final String NOTIFY_CONTEXT = "https://coar-repositories.org/notify";

    /** Media type of notifications. */
    public static final String JSON_LD = ActivityStreams.JSON_LD_MEDIA_TYPE;

    /** Media types inboxes and outboxes accept, for {@code Accept-Post}. */
    public static final String ACCEPTED_TYPES = JSON_LD + ", " + ActivityStreams.MEDIA_TYPE;

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
     * Validates a notification whose {@code @context} must state the Activity Streams context.
     *
     * @param notification the notification
     * @return the problems found; empty when the notification is valid
     */
    public static List<String> validate(final AsObject notification) {
        return validate(notification, false);
    }

    /**
     * Validates a notification.
     *
     * @param notification    the notification
     * @param contextImplied  whether the Activity Streams context is implied (the notification
     *                        came as {@code application/activity+json})
     * @return the problems found, COAR Notify rules first; empty when the notification is valid
     */
    public static List<String> validate(final AsObject notification, final boolean contextImplied) {
        final List<Violation> errors = new ArrayList<>();
        final List<Object> contexts = notification.context();
        if (!contextImplied && !contexts.contains(AS_CONTEXT)) {
            errors.add(new Violation("@context", "must include " + AS_CONTEXT));
        }
        if (!contexts.contains(NOTIFY_CONTEXT)) {
            errors.add(new Violation("@context", "must include " + NOTIFY_CONTEXT));
        }
        if (notification.id().filter(CoarNotify::isUri).isEmpty()) {
            errors.add(new Violation("id", "must be a URI, for example urn:uuid:<uuid>"));
        }
        if (notification.types().stream().noneMatch(ACTIVITY_TYPES::contains)) {
            errors.add(new Violation("type", "must include one of " + ACTIVITY_TYPES));
        }
        validateService(notification, "origin", errors);
        validateService(notification, "target", errors);
        final Optional<Object> object = notification.get("object");
        if (!(object.orElse(null) instanceof Map<?, ?>)
                || notification.object().getFirst().id().filter(CoarNotify::isUri).isEmpty()) {
            errors.add(new Violation("object", "must be an object with a URI id"));
        }
        // Activity Streams rules, for the properties COAR Notify has not already found fault with
        final Set<String> reported = new HashSet<>();
        errors.forEach(error -> reported.add(root(error.path())));
        ActivityStreamsValidator.lenient().validate(notification).stream()
            .filter(violation -> !reported.contains(root(violation.path())))
            .forEach(errors::add);
        return errors.stream().map(Violation::toString).toList();
    }

    private static void validateService(final AsObject notification, final String name,
            final List<Violation> errors) {
        if (!(notification.get(name).orElse(null) instanceof Map<?, ?>)) {
            errors.add(new Violation(name, "must be an object with id, inbox and type Service"));
            return;
        }
        final AsObject service = notification.objects(name).getFirst();
        if (service.id().filter(CoarNotify::isUri).isEmpty()) {
            errors.add(new Violation(name + ".id", "must be a URI"));
        }
        if (service.string("inbox").filter(CoarNotify::isUri).isEmpty()) {
            errors.add(new Violation(name + ".inbox", "must be a URI"));
        }
        if (!service.isA(AsType.SERVICE)) {
            errors.add(new Violation(name + ".type", "must be Service"));
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
     * Returns the first IRI a property of a notification refers to.
     *
     * @param notification the notification
     * @param name         the property, for example {@code origin}
     * @return the IRI (a plain IRI or the {@code id} of an embedded object), or {@code null}
     */
    public static String idOf(final AsObject notification, final String name) {
        final List<String> references = notification.references(name);
        return references.isEmpty() ? null : references.getFirst();
    }

    private static String root(final String path) {
        int end = path.length();
        final int dot = path.indexOf('.');
        final int bracket = path.indexOf('[');
        if (dot >= 0) {
            end = dot;
        }
        if (bracket >= 0 && bracket < end) {
            end = bracket;
        }
        return path.substring(0, end);
    }

    /**
     * Tells whether a value is an absolute URI.
     *
     * @param value the value
     * @return {@code true} for an absolute URI string
     */
    static boolean isUri(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return java.net.URI.create(value.trim()).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
