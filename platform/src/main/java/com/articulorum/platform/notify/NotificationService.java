package com.articulorum.platform.notify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.LdpException;
import com.articulorum.platform.ldp.exception.MethodNotAllowedException;
import com.articulorum.platform.ldp.exception.PayloadTooLargeException;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;
import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.service.ResourceDeletionListener;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Linked Data Notifications inboxes and outboxes of LDP containers, speaking COAR Notify.
 *
 * <p>Every container (and so every community, sub-community and collection) has an inbox at
 * {@code <container>/fcr:inbox}, advertised with {@code Link: <...>; rel="http://www.w3.org/ns/ldp#inbox"}.
 * POSTing a COAR Notify notification ({@code application/ld+json}) stores it after validation;
 * GET lists the received notifications. Notifications can also be sent on behalf of a container
 * by POSTing them to {@code <container>/fcr:outbox}: the platform fills in a missing {@code id}
 * and {@code origin} (the container as a service with its inbox), delivers the notification to
 * {@code target.inbox} and records the outcome.</p>
 */
@Slf4j
@Service
public class NotificationService implements ResourceDeletionListener {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final NotificationRepository notifications;

    private final LdpResourceRepository resources;

    private final JsonMapper json;

    private final RestClient client;

    /**
     * Creates the service.
     *
     * @param notifications stored notifications
     * @param resources     the resource repository
     * @param json          JSON mapper
     * @param builder       HTTP client builder for outgoing notifications
     */
    public NotificationService(final NotificationRepository notifications, final LdpResourceRepository resources,
            final JsonMapper json, final RestClient.Builder builder) {
        this.notifications = notifications;
        this.resources = resources;
        this.json = json;
        this.client = builder.build();
    }

    /**
     * Receives a notification into a container's inbox.
     *
     * @param path    the container path
     * @param content the notification (JSON-LD)
     * @param agent   who posted it
     * @return the stored notification
     * @throws BadRequestException when the notification is not valid COAR Notify
     */
    @Transactional
    public NotificationEntity receive(final String path, final byte[] content, final String agent) {
        final LdpResource container = container(path);
        final Map<String, Object> notification = parse(content);
        final List<String> errors = CoarNotify.validate(notification);
        if (!errors.isEmpty()) {
            throw new BadRequestException("Not a valid COAR Notify notification: " + String.join("; ", errors), null);
        }
        final NotificationEntity entity = toEntity(container.getPath(), notification,
            NotificationEntity.Direction.INBOUND, agent);
        entity.setStatus(NotificationEntity.Status.RECEIVED);
        notifications.save(entity);
        log.info("Received {} notification {} in /{}", entity.getPattern(), entity.getNotificationId(),
            container.getPath());
        return entity;
    }

    /**
     * Sends a notification on behalf of a container.
     *
     * @param path    the container path
     * @param content the notification (JSON-LD); {@code id}, {@code @context} and {@code origin} are
     *                filled in when missing
     * @param base    the server base (to describe the container as the origin service)
     * @param agent   who sends it
     * @return the recorded notification, with its delivery status
     * @throws BadRequestException when the notification is not valid COAR Notify
     */
    @Transactional
    public NotificationEntity send(final String path, final byte[] content, final String base, final String agent) {
        final LdpResource container = container(path);
        final Map<String, Object> notification = complete(parse(content), base, container);
        final List<String> errors = CoarNotify.validate(notification);
        if (!errors.isEmpty()) {
            throw new BadRequestException("Not a valid COAR Notify notification: " + String.join("; ", errors), null);
        }
        final NotificationEntity entity = toEntity(container.getPath(), notification,
            NotificationEntity.Direction.OUTBOUND, agent);
        final String inbox = (String) ((Map<?, ?>) notification.get("target")).get("inbox");
        try {
            client.post()
                .uri(inbox)
                .contentType(MediaType.parseMediaType(CoarNotify.JSON_LD))
                .body(entity.getContent())
                .retrieve()
                .toBodilessEntity();
            entity.setStatus(NotificationEntity.Status.SENT);
        } catch (RestClientException | IllegalArgumentException e) {
            entity.setStatus(NotificationEntity.Status.FAILED);
            entity.setStatusDetail(truncate(e.getMessage(), 2048));
            log.warn("Unable to deliver notification {} to {}: {}", entity.getNotificationId(), inbox, e.getMessage());
        }
        notifications.save(entity);
        return entity;
    }

    /**
     * Lists the notifications of a container, newest first.
     *
     * @param path      the container path
     * @param direction received or sent
     * @return the notifications
     */
    @Transactional(readOnly = true)
    public List<NotificationEntity> list(final String path, final NotificationEntity.Direction direction) {
        final LdpResource container = container(path);
        return notifications.findByPathAndDirectionOrderByReceivedDesc(container.getPath(), direction);
    }

    /**
     * Returns one received notification.
     *
     * @param path the container path
     * @param id   the notification's local identifier
     * @return the notification
     * @throws ResourceNotFoundException when it does not exist in that inbox
     */
    @Transactional(readOnly = true)
    public NotificationEntity get(final String path, final String id) {
        final LdpResource container = container(path);
        final UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException(container.getPath() + "/fcr:inbox/" + id);
        }
        return notifications.findById(uuid)
            .filter(found -> found.getPath().equals(container.getPath()))
            .orElseThrow(() -> new ResourceNotFoundException(container.getPath() + "/fcr:inbox/" + id));
    }

    @Override
    @Transactional
    public void subtreeDeleted(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        notifications.deleteSubtree(normalized, normalized + "/");
    }

    /**
     * Returns the IRI of a container's inbox.
     *
     * @param base the server base
     * @param path the container path
     * @return {@code <container>/fcr:inbox}
     */
    public static String inboxIri(final String base, final String path) {
        final String iri = Iris.resolve(base, ResourcePaths.toRelativeIri(path));
        return (iri.endsWith("/") ? iri : iri + "/") + "fcr:inbox";
    }

    /**
     * Fills in what a client may leave out when sending: the contexts, an {@code id} and the
     * {@code origin} (the container as a service with its inbox).
     *
     * @param notification the notification
     * @param base         the server base
     * @param container    the sending container
     * @return the completed notification
     */
    static Map<String, Object> complete(final Map<String, Object> notification, final String base,
            final LdpResource container) {
        final Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("@context", List.of(CoarNotify.AS_CONTEXT, CoarNotify.NOTIFY_CONTEXT));
        completed.putAll(notification);
        completed.putIfAbsent("id", "urn:uuid:" + UUID.randomUUID());
        if (!(notification.get("origin") instanceof Map<?, ?>)) {
            final Map<String, Object> origin = new LinkedHashMap<>();
            origin.put("id", Iris.resolve(base, container.getRelativeIri()));
            origin.put("inbox", inboxIri(base, container.getPath()));
            origin.put("type", "Service");
            completed.put("origin", origin);
        }
        return completed;
    }

    private LdpResource container(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        final LdpResource resource = resources.findByPath(normalized)
            .orElseThrow(() -> new ResourceNotFoundException(normalized));
        if (!resource.isContainer()) {
            throw new MethodNotAllowedException("Only containers have notification inboxes",
                java.util.Set.of(HttpMethod.OPTIONS));
        }
        return resource;
    }

    private Map<String, Object> parse(final byte[] content) {
        if (content.length > NotificationEntity.MAX_LENGTH) {
            throw new PayloadTooLargeException(NotificationEntity.MAX_LENGTH);
        }
        try {
            final Map<String, Object> value = json.readValue(content, JSON_OBJECT);
            if (value == null) {
                throw new BadRequestException("The notification must be a JSON object", null);
            }
            return value;
        } catch (JacksonException e) {
            throw new BadRequestException("The notification is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private NotificationEntity toEntity(final String path, final Map<String, Object> notification,
            final NotificationEntity.Direction direction, final String agent) {
        final NotificationEntity entity = new NotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setPath(path);
        entity.setDirection(direction);
        entity.setNotificationId(truncate(CoarNotify.idOf(notification.get("id")), 2048));
        final var types = CoarNotify.strings(notification.get("type"));
        entity.setTypes(truncate(String.join(" ", types), 1024));
        entity.setPattern(truncate(CoarNotify.pattern(types), 128));
        entity.setOrigin(truncate(CoarNotify.idOf(notification.get("origin")), 2048));
        entity.setTarget(truncate(CoarNotify.idOf(notification.get("target")), 2048));
        entity.setObject(truncate(CoarNotify.idOf(notification.get("object")), 2048));
        entity.setActor(truncate(CoarNotify.idOf(notification.get("actor")), 2048));
        entity.setAgent(truncate(agent, 256));
        try {
            entity.setContent(new String(json.writeValueAsBytes(notification), StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new LdpException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store the notification",
                new HttpHeaders(), e);
        }
        if (entity.getContent().length() > NotificationEntity.MAX_LENGTH) {
            throw new PayloadTooLargeException(NotificationEntity.MAX_LENGTH);
        }
        entity.setReceived(Instant.now());
        return entity;
    }

    private static String truncate(final String value, final int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

}
