package com.example.demo.dto;

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
 * seatsTaken counts the owner; signedUp says whether the person asking holds
 * a seat. Neither is on the entity, so WantService works them out. status
 * is ACTIVE, CLOSED, ENDED or CANCELLED; a full want is still ACTIVE.
 * happening says it is live now, whatever its status says about joining.
 */
public record WantResponse(Long id, String message, Category category, Long userId, String username,
        String imageUrl, int capacity, int seatsTaken, boolean signedUp, WantStatus status, boolean happening) {

    public static WantResponse from(Want want, int seatsTaken, boolean signedUp) {
        return new WantResponse(
                want.getId(),
                want.getMessage(),
                want.getCategory(),
                want.getUser().getId(),
                want.getUser().getUsername(),
                want.getUser().getImageUrl(),
                want.getCapacity(),
                seatsTaken,
                signedUp,
                want.getStatus(),
                want.isHappening());
    }
}
