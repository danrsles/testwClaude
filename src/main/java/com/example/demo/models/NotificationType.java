package com.example.demo.models;

/** What a notification is about. Stored by name, so the migration's check must list each one. */
public enum NotificationType {
    /** The actor started following the recipient. */
    FOLLOW,
    /** The recipient created a want; recipient and actor are the same person. */
    WANT_CREATED,
    /** The actor took a seat on the recipient's want. */
    WANT_SIGNUP,
    /** The actor gave up their seat on the recipient's want. */
    WANT_WITHDRAWN,
    /** The actor, the owner, renamed or recategorised a want the recipient is signed up for. */
    WANT_UPDATED,
    /** The actor, the owner, closed a want the recipient is signed up for, fixing its lineup. */
    WANT_CLOSED,
    /** The actor, the owner, reopened a closed want the recipient is signed up for. */
    WANT_REOPENED,
    /** The actor, the owner, cancelled a want the recipient is signed up for. */
    WANT_CANCELLED
}
