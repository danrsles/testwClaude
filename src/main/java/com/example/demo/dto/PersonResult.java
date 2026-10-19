package com.example.demo.dto;

import com.example.demo.models.User;

/**
 * Another user as a people search shows them: UserSummary's public fields,
 * plus whether the signed-in user already follows them. Never the User
 * entity, which holds that user's email.
 */
public record PersonResult(Long id, String username, String imageUrl, boolean following) {

    public static PersonResult from(User user, boolean following) {
        return new PersonResult(user.getId(), user.getUsername(), user.getImageUrl(), following);
    }
}
