package com.example.demo.dto;

import java.util.List;

/**
 * A want's lineup after a signup or withdrawal, as the person who made it
 * sees it. The lineup names people by username only, in the order they
 * signed up, the owner first. seatsTaken counts the owner too.
 */
public record SignupEventDetails(Long wantId, String message, int capacity, int seatsTaken,
        List<String> lineup, boolean signedUp) {
}
