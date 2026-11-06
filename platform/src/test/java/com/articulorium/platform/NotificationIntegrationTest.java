package com.articulorium.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.articulorium.domain.vocabulary.Ldp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the COAR Notify inboxes and outboxes of containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationIntegrationTest {

    private static final String BASE = "http://localhost";

    private static final String JSON_LD = "application/ld+json";

    private static final String ACTIVITY_JSON = "application/activity+json";

    @Autowired
    private MockMvc mvc;

    private String container() throws Exception {
        return mvc.perform(post("/").header("Slug", "inbox-" + UUID.randomUUID().toString().substring(0, 8))
                .contentType("text/turtle").content(""))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());
    }

    static String announceReview(final String target) {
        return """
            {
              "@context": ["https://www.w3.org/ns/activitystreams", "https://coar-repositories.org/notify"],
              "id": "urn:uuid:%s",
              "type": ["Announce", "coar-notify:ReviewAction"],
              "origin": {"id": "https://review.example.org/", "inbox": "https://review.example.org/inbox",
                         "type": "Service"},
              "target": {"id": "%s", "inbox": "%s/fcr:inbox", "type": "Service"},
              "object": {"id": "https://review.example.org/reviews/1", "type": ["Page", "sorg:Review"]},
              "actor": {"id": "https://review.example.org/", "type": "Service", "name": "Review service"}
            }
            """.formatted(UUID.randomUUID(), target, target);
    }

    /** Containers advertise their inbox, accept notifications and list them. */
    @Test
    void inboxesReceiveAndListNotifications() throws Exception {
        final String container = container();
        mvc.perform(get(container))
            .andExpect(header().stringValues(HttpHeaders.LINK,
                hasItem(containsString(container + "/fcr:inbox>; rel=\"" + Ldp.INBOX + "\""))));

        final String location = mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD)
                .content(announceReview(BASE + container)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        mvc.perform(get(container + "/fcr:inbox").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notifications.length()").value(1))
            .andExpect(jsonPath("$.notifications[0].pattern").value("Announce Review"))
            .andExpect(jsonPath("$.notifications[0].origin").value("https://review.example.org/"))
            .andExpect(jsonPath("$.notifications[0].uri").value(location));
        mvc.perform(get(container + "/fcr:inbox").accept("text/turtle"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(location.substring(location.lastIndexOf('/') + 1))));
        mvc.perform(get(location.substring(BASE.length())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("coar-notify:ReviewAction")));
    }

    /** Notifications that are not COAR Notify are rejected; only containers have inboxes. */
    @Test
    void invalidNotificationsAreRejected() throws Exception {
        final String container = container();
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD).content("{\"type\":\"Note\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD).content("not json"))
            .andExpect(status().isBadRequest());
        final String file = mvc.perform(post(container).contentType("text/plain").content("x"))
            .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION)
            .substring(BASE.length());
        mvc.perform(post(file + "/fcr:inbox").contentType(JSON_LD).content(announceReview(BASE + file)))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(container + "/fcr:inbox/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    /**
     * Activity Streams documents are accepted as application/activity+json, whose Activity Streams
     * context is implied and stated when stored, and served in either media type.
     */
    @Test
    void inboxesSpeakActivityJson() throws Exception {
        final String container = container();
        final String notification = announceReview(BASE + container)
            .replace("\"https://www.w3.org/ns/activitystreams\", ", "");
        final String location = mvc.perform(post(container + "/fcr:inbox").contentType(ACTIVITY_JSON)
                .content(notification))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader(HttpHeaders.LOCATION).substring(BASE.length());

        mvc.perform(get(location))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(JSON_LD)))
            .andExpect(jsonPath("$['@context'][0]").value("https://www.w3.org/ns/activitystreams"))
            .andExpect(jsonPath("$['@context'][1]").value("https://coar-repositories.org/notify"));
        mvc.perform(get(location).accept(ACTIVITY_JSON + ", " + JSON_LD + ";q=0.5"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(ACTIVITY_JSON)))
            .andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.ACCEPT)));
        mvc.perform(get(location).accept(JSON_LD + ", " + ACTIVITY_JSON + ";q=0.5"))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(JSON_LD)));

        // as application/ld+json, the Activity Streams context must be stated
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD).content(notification))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("@context must include https://www.w3.org/ns/activitystreams")));
        mvc.perform(options(container + "/fcr:inbox"))
            .andExpect(header().string("Accept-Post", JSON_LD + ", " + ACTIVITY_JSON));
    }

    /** Malformed and invalid Activity Streams documents are rejected predictably. */
    @Test
    void malformedActivityStreamsAreRejected() throws Exception {
        final String container = container();
        final String notification = announceReview(BASE + container);
        mvc.perform(post(container + "/fcr:inbox").contentType("text/plain").content(notification))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("Accept-Post", JSON_LD + ", " + ACTIVITY_JSON));
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD)
                .content(notification.replace("\"type\": [\"Announce\"", "\"id\": \"urn:x\", \"type\": [\"Announce\"")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("not valid JSON")));
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD).content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("must be a JSON object")));
        mvc.perform(post(container + "/fcr:inbox").contentType(JSON_LD)
                .content(notification.replace("\"actor\": {", "\"published\": \"today\", \"actor\": {")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("published must be an RFC 3339 date-time")));
        mvc.perform(get(container + "/fcr:inbox").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.notifications.length()").value(0));
    }

    /** Sending fills in the origin and records failed deliveries. */
    @Test
    void outboxRecordsDeliveries() throws Exception {
        final String container = container();
        final String offer = """
            {
              "type": ["Offer", "coar-notify:ReviewAction"],
              "target": {"id": "http://127.0.0.1:9/", "inbox": "http://127.0.0.1:9/inbox", "type": "Service"},
              "object": {"id": "https://doi.org/10.5555/12345"}
            }
            """;
        mvc.perform(post(container + "/fcr:outbox").contentType(JSON_LD).content(offer))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.origin").value(BASE + container));
        mvc.perform(get(container + "/fcr:outbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notifications[0].pattern").value("Offer Review"));
    }

}
