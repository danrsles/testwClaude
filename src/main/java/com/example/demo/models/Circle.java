package com.example.demo.models;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A group, like a group chat: one owner, who created it, and members, who are
 * rows in circle_members rather than a collection here (as Want has no list
 * of signups). Deleting a circle archives it, because wants will point at it.
 */
@Entity
@Table(name = "circles")
public class Circle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Null while the circle exists; set when its owner deletes it. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Circle() {
    }

    public Circle(User owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    /** What deleting does: the row stays, hidden everywhere. */
    public void archive(Instant now) {
        this.archivedAt = now;
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }
}
