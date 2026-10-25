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
    WANT_CANCELLED,
    /** The actor, the owner, started a want the recipient is signed up for: it is happening now. */
    WANT_STARTED,
    /** The actor, the owner, ended a happening want the recipient is signed up for. */
    WANT_ENDED,
    /** The actor, the owner, removed the seat limit on a want the recipient is signed up for: bring anyone. */
    WANT_OPEN_INVITE,
    /** The actor, the owner, limited an open want the recipient is signed up for to the people already in. */
    WANT_LIMITED,
    /** The actor, the owner, set, moved or removed the deadline of a want the recipient is signed up for. */
    WANT_DEADLINE_CHANGED,
    /**
     * A want the recipient is signed up for, or owns, closed at its deadline.
     * Nobody acted, so the actor is the owner, and for the owner the recipient too.
     */
    WANT_EXPIRED
}
