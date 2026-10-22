package com.example.demo.dto;

import com.example.demo.models.Category;

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
 */
public record WantRequest(
        @NotBlank String message,
        @NotNull Category category,
        @Min(1) Integer capacity) {

    /** The capacity for a new want: 1, the owner alone, unless one was given. */
    public int capacityOnCreate() {
        return capacity == null ? 1 : capacity;
    }
}
