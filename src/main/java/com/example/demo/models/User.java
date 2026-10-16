package com.example.demo.models;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A signed-in person. Holds private details (email, last sign-in), so this
 * entity is only ever serialized for its own user: GET /me and their own
 * profile. Anything listing other users' data, like the wants list, goes
 * through a response type that copies the public fields (see WantResponse).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * The Clerk user id (the token's "sub"), set when the user is created on
     * first sign-in. Null only for users seeded before logins existed. Not a
     * secret: it identifies an account but cannot sign in as it.
     */
    @Column(name = "clerk_id", unique = true)
    private String clerkId;

    /** The primary email address in Clerk, for notifications. Copied from the session token. */
    @Column(length = 320)
    private String email;

    /** When the user last signed in, per Clerk. Copied from the session token. */
    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    protected User() {
    }

    public User(String username) {
        this.username = username;
    }

    public User(String username, String clerkId) {
        this.username = username;
        this.clerkId = clerkId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getClerkId() {
        return clerkId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getLastSignInAt() {
        return lastSignInAt;
    }

    public void setLastSignInAt(Instant lastSignInAt) {
        this.lastSignInAt = lastSignInAt;
    }
}
