package com.example.demo.dto;

import com.example.demo.models.User;

/**
 * Another user as the API shows them: the same public fields WantResponse
 * names an owner by. Never the User entity, which holds that user's email.
 */
public record UserSummary(Long id, String username, String imageUrl) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getImageUrl());
    }
}
