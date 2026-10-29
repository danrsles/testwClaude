package com.example.demo.dto;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.Want;
import com.example.demo.models.WantStatus;

/**
 * A want as the API returns it. Your wants appear in your followers' lists,
 * so this names its owner by userId, username and imageUrl (their Clerk
 * profile picture, public by design) only, and never embeds the User entity,
 * which holds that user's email. A new field reaches the wants list only by being
 * added here on purpose.
 *
 * capacity is null for an open invite, which has no seat limit. lineup is
 * everyone holding a seat, the owner first, in signup order, by the same
 * public fields as the owner. seatsTaken is its size; signedUp says whether
 * the person asking is in it. None is on the entity, so WantService looks
 * them up. status is ACTIVE, CLOSED, ENDED or CANCELLED; a full want is
 * still ACTIVE.
 * happening says it is live now, whatever its status says about joining.
 * expiresAt is the deadline, or null for none; once it has passed, status
 * says CLOSED.
 */
public record WantResponse(Long id, String message, Category category, Long userId, String username,
        String imageUrl, Integer capacity, int seatsTaken, List<UserSummary> lineup, boolean signedUp,
        WantStatus status, boolean happening, Instant expiresAt, CircleSummary circle) {

    /** now decides whether the deadline has passed. */
    public static WantResponse from(Want want, List<UserSummary> lineup, boolean signedUp, Instant now) {
        return new WantResponse(
                want.getId(),
                want.getMessage(),
                want.getCategory(),
                want.getUser().getId(),
                want.getUser().getUsername(),
                want.getUser().getImageUrl(),
                want.getCapacity(),
                lineup.size(),
                lineup,
                signedUp,
                want.getStatus(now),
                want.isHappening(),
                want.getExpiresAt(),
                want.getCircle() == null ? null : CircleSummary.from(want.getCircle()));
    }
}
