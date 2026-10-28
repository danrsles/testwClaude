package com.example.demo.models;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Something the recipient should hear about, done by the actor. Written for
 * every event, signed in or not, so the table alone is the record of what
 * someone has missed. Unidirectional, like Follow: a User has no list of
 * notifications. Rows are written by NotificationRepository.notify, so the
 * database fills in createdAt.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Null until the recipient has seen it; set by NotificationRepository.markRead. */
    @Column(name = "read_at", insertable = false, updatable = false)
    private Instant readAt;

    /** The want it is about, or null for a follow. A bare id: the want may have changed since. */
    @Column(name = "want_id", insertable = false, updatable = false)
    private Long wantId;

    /** The want's message when this was written, so it still reads correctly after an edit. */
    @Column(name = "want_message", insertable = false, updatable = false)
    private String wantMessage;

    /** For WANT_UPDATED, the name the want had before; null if the name did not change. */
    @Column(name = "previous_message", insertable = false, updatable = false)
    private String previousMessage;

    /** The circle it is about, for CIRCLE_ADDED; null otherwise. A bare id, like wantId. */
    @Column(name = "circle_id", insertable = false, updatable = false)
    private Long circleId;

    /** The circle's name when this was written, so it still reads correctly after a rename. */
    @Column(name = "circle_name", insertable = false, updatable = false)
    private String circleName;

    protected Notification() {
    }

    public Notification(User recipient, User actor, NotificationType type) {
        this.recipient = recipient;
        this.actor = actor;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public User getActor() {
        return actor;
    }

    public NotificationType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Long getWantId() {
        return wantId;
    }

    public String getWantMessage() {
        return wantMessage;
    }

    public String getPreviousMessage() {
        return previousMessage;
    }

    public Long getCircleId() {
        return circleId;
    }

    public String getCircleName() {
        return circleName;
    }
}
