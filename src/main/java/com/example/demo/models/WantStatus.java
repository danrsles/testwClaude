package com.example.demo.models;

/**
 * Where a want is in its life, worked out from closed_at and cancelled_at
 * rather than stored. Full is not a status: it is seatsTaken >= capacity.
 */
public enum WantStatus {
    /** Taking signups while it has a free seat. */
    ACTIVE,
    /** The owner fixed the lineup; they can reopen it or cancel it. */
    CLOSED,
    /** Called off by the owner and hidden from the feed. Final. */
    CANCELLED
}
