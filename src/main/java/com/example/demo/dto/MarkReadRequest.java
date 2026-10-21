package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body for marking notifications read: the newest id the user was shown.
 * Everything up to it is marked; anything newer, which they have not seen,
 * stays unread.
 */
public record MarkReadRequest(@NotNull Long upTo) {
}
