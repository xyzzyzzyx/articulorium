package com.articulorum.platform.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void acceptsAValidNotification() {
        assertThat(CoarNotify.validate(valid())).isEmpty();
    }

    @Test
    void reportsEveryProblem() {
        final Map<String, Object> notification = valid();
        notification.put("@context", "https://www.w3.org/ns/activitystreams");
        notification.put("id", "not a uri");
        notification.put("type", "Note");
        notification.put("origin", Map.of("id", "https://a.example/"));
        notification.remove("object");

        assertThat(CoarNotify.validate(notification)).hasSize(6)
            .anyMatch(error -> error.contains("coar-repositories.org/notify"))
            .anyMatch(error -> error.startsWith("id"))
            .anyMatch(error -> error.startsWith("type"))
            .anyMatch(error -> error.startsWith("origin.inbox"))
            .anyMatch(error -> error.startsWith("origin.type"))
            .anyMatch(error -> error.startsWith("object"));
    }

    @Test
    void namesPatterns() {
        assertThat(CoarNotify.pattern(List.of("Offer", "coar-notify:EndorsementAction"))).isEqualTo("Offer Endorsement");
        assertThat(CoarNotify.pattern(List.of("Announce", "RelationshipAction"))).isEqualTo("Announce Relationship");
        assertThat(CoarNotify.pattern(List.of("Accept"))).isEqualTo("Accept");
    }

    @Test
    void readsIdsAndStrings() {
        assertThat(CoarNotify.idOf(Map.of("id", "https://x/"))).isEqualTo("https://x/");
        assertThat(CoarNotify.idOf("https://y/")).isEqualTo("https://y/");
        assertThat(CoarNotify.idOf(42)).isNull();
        assertThat(CoarNotify.strings(List.of("a", 1, "b"))).containsExactly("a", "b");
    }

}
