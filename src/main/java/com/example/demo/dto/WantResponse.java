package com.example.demo.dto;

import com.example.demo.models.Category;
import com.example.demo.models.Want;

/**
 * A want as the API returns it. Every signed-in user sees every want, so this
 * names its owner by public fields only (id, username, nickname) and never
 * embeds the User entity, which holds that user's email. A new field reaches
 * the wants list only by being added here on purpose.
 *
 * @param nickname the owner's nickname, or null if they have not set one
 */
public record WantResponse(Long id, String message, Category category, Long userId, String username,
        String nickname) {

    public static WantResponse from(Want want) {
        return new WantResponse(
                want.getId(),
                want.getMessage(),
                want.getCategory(),
                want.getUser().getId(),
                want.getUser().getUsername(),
                want.getUser().getNickname());
    }
}
