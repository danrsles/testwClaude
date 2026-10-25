package com.example.demo.dto;

import java.time.Instant;

import com.example.demo.models.Category;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body for creating or replacing a want. PUT is a full replacement, so it
 * requires the same fields as POST. No userId: the owner is whoever is signed
 * in, so accepting one would let a client post as somebody else.
 *
 * capacity counts the owner, so it is at least 1. It is optional so clients
 * that predate signups keep working: left out, a new want takes just its
 * owner, and an edit leaves the capacity as it was.
 *
 * openInvite true asks for no seat limit, on create or to open a limited
 * want. false limits an open want to the people already in it. It is a flag
 * rather than a null capacity because a missing capacity already means 1 or
 * "keep". Left out, it changes nothing. Sending it, true or false, with a
 * capacity is a 400: one contradicts the number, the other would ignore it.
 *
 * expiresAt is the deadline, which must be in the future (400). Left out, an
 * edit keeps the deadline as it was, for the same reason as capacity, so
 * removing it takes noDeadline true. Sending both is a 400.
 */
public record WantRequest(
        @NotBlank String message,
        @NotNull Category category,
        @Min(1) Integer capacity,
        Boolean openInvite,
        @Future Instant expiresAt,
        Boolean noDeadline) {

    /** The capacity for a new want: none for an open invite, else 1, the owner alone, unless one was given. */
    public Integer capacityOnCreate() {
        if (Boolean.TRUE.equals(openInvite)) {
            return null;
        }
        return capacity == null ? 1 : capacity;
    }

    /** A capacity and openInvite together fail validation, whichever way openInvite is set. */
    @AssertTrue(message = "give a capacity or openInvite, not both")
    public boolean isCapacityOrOpenInvite() {
        return openInvite == null || capacity == null;
    }

    /** A deadline and noDeadline together fail validation: one sets it, the other removes it. */
    @AssertTrue(message = "give expiresAt or noDeadline, not both")
    public boolean isDeadlineOrNoDeadline() {
        return expiresAt == null || !Boolean.TRUE.equals(noDeadline);
    }
}
