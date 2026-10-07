package com.example.demo.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Contact and display details for a user. A separate entity rather than columns
 * on User, so email stays out of the user object every want embeds. The link is
 * one-way, like Want's, and eager because open-in-view is off.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, length = 100)
    private String nickname;

    /** Avatar location. Null until S3 uploads exist; the frontend shows a placeholder. */
    @Column(name = "s3_url", length = 1024)
    private String s3Url;

    protected UserProfile() {
    }

    public UserProfile(User user, String email, String nickname, String s3Url) {
        this.user = user;
        this.email = email;
        this.nickname = nickname;
        this.s3Url = s3Url;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getS3Url() {
        return s3Url;
    }

    public void setS3Url(String s3Url) {
        this.s3Url = s3Url;
    }
}
