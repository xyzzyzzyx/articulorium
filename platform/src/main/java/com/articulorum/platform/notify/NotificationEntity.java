package com.articulorum.platform.notify;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A COAR Notify (Linked Data Notifications) notification received in, or sent from, the inbox of
 * an LDP container.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ldn_notifications", indexes = {
    @Index(name = "ldn_notifications_path_idx", columnList = "path,direction")
})
public class NotificationEntity {

    /** Largest stored notification in characters. */
    public static final int MAX_LENGTH = 262_144;

    /** Whether a notification was received or sent. */
    public enum Direction {

        /** Received in the resource's inbox. */
        INBOUND,

        /** Sent on behalf of the resource. */
        OUTBOUND

    }

    /** Delivery state of a notification. */
    public enum Status {

        /** Received and accepted. */
        RECEIVED,

        /** Delivered to the target inbox. */
        SENT,

        /** The target inbox did not accept it. */
        FAILED

    }

    @Id
    private UUID id;

    /** Path of the container whose inbox (or outbox) holds the notification. */
    @Column(name = "path", nullable = false, length = 1024)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** Details of a failed delivery. */
    @Column(name = "status_detail", length = 2048)
    private String statusDetail;

    /** The notification's own {@code id} (usually {@code urn:uuid:...}). */
    @Column(name = "notification_id", length = 2048)
    private String notificationId;

    /** Its types, space separated (for example {@code Announce coar-notify:ReviewAction}). */
    @Column(name = "types", length = 1024)
    private String types;

    /** The COAR Notify pattern recognised from the types, for example {@code Announce Review}. */
    @Column(name = "pattern", length = 128)
    private String pattern;

    @Column(name = "origin_id", length = 2048)
    private String origin;

    @Column(name = "target_id", length = 2048)
    private String target;

    @Column(name = "object_id", length = 2048)
    private String object;

    @Column(name = "actor_id", length = 2048)
    private String actor;

    /** The agent that posted (received) or sent the notification. */
    @Column(name = "agent", length = 256)
    private String agent;

    /** The notification as JSON-LD. */
    @Column(name = "content", nullable = false, length = MAX_LENGTH)
    private String content;

    @Column(name = "received", nullable = false)
    private Instant received;

}
