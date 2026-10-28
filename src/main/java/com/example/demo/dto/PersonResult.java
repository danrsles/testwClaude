package com.example.demo.dto;

import com.example.demo.models.User;

/**
 * Another user as a people search and GET /follows show them: UserSummary's
 * public fields, plus whether the signed-in user already follows them and
 * hears about their new wants (false when muted or not followed). Never the
 * User entity, which holds that user's email.
 */
public record PersonResult(Long id, String username, String imageUrl, boolean following, boolean notifyNewWants) {

    public static PersonResult from(User user, boolean following, boolean notifyNewWants) {
        return new PersonResult(user.getId(), user.getUsername(), user.getImageUrl(), following, notifyNewWants);
    }
}
