package com.articulorium.platform.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.activitystreams.AsObject;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CoarNotify}.
 */
class CoarNotifyTest {

    static Map<String, Object> valid() {
        final Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("@context", List.of(CoarNotify.AS_CONTEXT, CoarNotify.NOTIFY_CONTEXT));
        notification.put("id", "urn:uuid:0370c0fb-bb78-4a9b-87f5-bed307a509dd");
        notification.put("type", List.of("Offer", "coar-notify:EndorsementAction"));
        notification.put("origin", Map.of("id", "https://a.example/", "inbox", "https://a.example/inbox",
            "type", "Service"));
        notification.put("target", Map.of("id", "https://b.example/", "inbox", "https://b.example/inbox",
            "type", "Service"));
        notification.put("object", Map.of("id", "https://doi.org/10.5555/1"));
        return notification;
    }

    private static List<String> validate(final Map<String, Object> notification) {
        return CoarNotify.validate(AsObject.of(notification));
    }

    @Test
    void acceptsAValidNotification() {
        assertThat(validate(valid())).isEmpty();
    }

    @Test
    void reportsEveryProblem() {
        final Map<String, Object> notification = valid();
        notification.put("@context", "https://www.w3.org/ns/activitystreams");
        notification.put("id", "not a uri");
        notification.put("type", "Note");
        notification.put("origin", Map.of("id", "https://a.example/"));
        notification.remove("object");

        assertThat(validate(notification)).hasSize(6)
            .anyMatch(error -> error.contains("coar-repositories.org/notify"))
            .anyMatch(error -> error.startsWith("id"))
            .anyMatch(error -> error.startsWith("type"))
            .anyMatch(error -> error.startsWith("origin.inbox"))
            .anyMatch(error -> error.startsWith("origin.type"))
            .anyMatch(error -> error.startsWith("object"));
    }

    @Test
    void reportsActivityStreamsProblems() {
        final Map<String, Object> notification = valid();
        notification.put("published", "last week");
        notification.put("actor", Map.of("id", "https://a.example/", "type", "Service", "name", List.of()));

        assertThat(validate(notification)).containsExactly(
            "published must be an RFC 3339 date-time with a time zone offset",
            "actor.name must be a string");
    }

    @Test
    void doesNotReportAPropertyTwice() {
        final Map<String, Object> notification = valid();
        notification.put("object", Map.of("id", "doi:10.5555/1 with spaces"));

        assertThat(validate(notification)).containsExactly("object must be an object with a URI id");
    }

    @Test
    void impliesTheActivityStreamsContext() {
        final Map<String, Object> notification = valid();
        notification.put("@context", CoarNotify.NOTIFY_CONTEXT);

        assertThat(CoarNotify.validate(AsObject.of(notification), true)).isEmpty();
        assertThat(CoarNotify.validate(AsObject.of(notification), false))
            .containsExactly("@context must include " + CoarNotify.AS_CONTEXT);
    }

    @Test
    void namesPatterns() {
        assertThat(CoarNotify.pattern(List.of("Offer", "coar-notify:EndorsementAction"))).isEqualTo("Offer Endorsement");
        assertThat(CoarNotify.pattern(List.of("Announce", "RelationshipAction"))).isEqualTo("Announce Relationship");
        assertThat(CoarNotify.pattern(List.of("Accept"))).isEqualTo("Accept");
    }

    @Test
    void readsIds() {
        final AsObject notification = AsObject.of(Map.of("origin", Map.of("id", "https://x/"),
            "actor", "https://y/", "object", 42));
        assertThat(CoarNotify.idOf(notification, "origin")).isEqualTo("https://x/");
        assertThat(CoarNotify.idOf(notification, "actor")).isEqualTo("https://y/");
        assertThat(CoarNotify.idOf(notification, "object")).isNull();
        assertThat(CoarNotify.idOf(notification, "target")).isNull();
    }

}
