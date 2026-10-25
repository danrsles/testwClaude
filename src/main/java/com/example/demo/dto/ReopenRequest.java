package com.example.demo.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;

/**
 * Optional body for reopening a want. expiresAt, if given, is the new
 * deadline, and must be in the future (400). Without it the want keeps a
 * deadline still ahead, and loses one that has passed.
 */
public record ReopenRequest(@Future Instant expiresAt) {
}
