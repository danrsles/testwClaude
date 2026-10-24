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

@Entity
@Table(name = "wants")
public class Want {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * How many people the want takes, its owner included; null for an open
     * invite, which takes everyone who can see it. Can be raised, or removed,
     * but never lowered, and an open invite never gets one.
     */
    private Integer capacity = 1;

    /** When the owner fixed the lineup; null while it is open. Reopening clears it. */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** When the owner called it off; null unless cancelled. Final: nothing clears it. */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** When the owner started it, so it is happening; null until then. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When the owner ended it after it happened; null unless ended. Final: nothing clears it. */
    @Column(name = "ended_at")
    private Instant endedAt;

    protected Want() {
    }

    public Want(String message, Category category, User user) {
        this.message = message;
        this.category = category;
        this.user = user;
    }

    public Want(String message, Category category, User user, Integer capacity) {
        this(message, category, user);
        this.capacity = capacity;
    }

    public Long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    /**
     * Cancelled outranks ended, which outranks closed: a want closed, then
     * started and ended, is ENDED. Whether it is happening is not a status;
     * see isHappening.
     */
    public WantStatus getStatus() {
        if (cancelledAt != null) {
            return WantStatus.CANCELLED;
        }
        if (endedAt != null) {
            return WantStatus.ENDED;
        }
        return closedAt != null ? WantStatus.CLOSED : WantStatus.ACTIVE;
    }

    public void close(Instant at) {
        this.closedAt = at;
    }

    public void reopen() {
        this.closedAt = null;
    }

    public void cancel(Instant at) {
        this.cancelledAt = at;
    }

    /**
     * Live now: started, and neither ended nor cancelled. Independent of
     * closing, so an active want can be happening and still take signups.
     */
    public boolean isHappening() {
        return startedAt != null && endedAt == null && cancelledAt == null;
    }

    public void start(Instant at) {
        this.startedAt = at;
    }

    public void end(Instant at) {
        this.endedAt = at;
    }
}
