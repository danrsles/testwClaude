package com.example.demo.dto;

import java.util.List;

import com.example.demo.models.WantStatus;

/**
 * A want's lineup after a signup or withdrawal, as the person who made it
 * sees it. The lineup names people by username only, in the order they
 * signed up, the owner first. seatsTaken counts the owner too. capacity
 * is null for an open invite.
 */
public record SignupEventDetails(Long wantId, String message, Integer capacity, int seatsTaken,
        List<String> lineup, boolean signedUp, WantStatus status) {
}
