package com.articulorum.platform.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import org.junit.jupiter.api.Test;

/**
 * Tests the pure helpers of {@link NotificationService}.
 */
class NotificationServiceTest {

    @Test
    void completesContextIdAndOrigin() {
        final LdpResource container = new LdpResource("communities/c1", InteractionModel.BASIC_CONTAINER);
        final Map<String, Object> completed = NotificationService.complete(
            Map.of("type", List.of("Announce")), "http://localhost:8000/platform", container);

        assertThat(completed.get("@context")).isEqualTo(List.of(CoarNotify.AS_CONTEXT, CoarNotify.NOTIFY_CONTEXT));
        assertThat((String) completed.get("id")).startsWith("urn:uuid:");
        assertThat(completed.get("origin")).isEqualTo(Map.of(
            "id", "http://localhost:8000/platform/communities/c1",
            "inbox", "http://localhost:8000/platform/communities/c1/fcr:inbox",
            "type", "Service"));
    }

    @Test
    void keepsWhatTheClientSent() {
        final LdpResource container = new LdpResource("c", InteractionModel.BASIC_CONTAINER);
        final Map<String, Object> origin = Map.of("id", "https://o/", "inbox", "https://o/inbox", "type", "Service");
        final Map<String, Object> completed = NotificationService.complete(
            Map.of("id", "urn:uuid:1", "origin", origin, "@context", "x"), "http://h", container);

        assertThat(completed).containsEntry("id", "urn:uuid:1").containsEntry("origin", origin)
            .containsEntry("@context", "x");
        assertThat(NotificationService.inboxIri("http://h/", "")).isEqualTo("http://h/fcr:inbox");
    }

}
