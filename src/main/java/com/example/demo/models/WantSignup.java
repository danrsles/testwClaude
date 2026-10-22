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
 * One seat on a want. The owner holds one from the moment the want is
 * created. Unidirectional, like Follow: neither Want nor User has a list of
 * signups. Rows are written by WantSignupRepository.signUpIfAbsent, so the
 * database fills in createdAt.
 */
@Entity
@Table(name = "want_signups")
public class WantSignup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "want_id", nullable = false)
    private Want want;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected WantSignup() {
    }

    public Long getId() {
        return id;
    }

    public Want getWant() {
        return want;
    }

    public User getUser() {
        return user;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
