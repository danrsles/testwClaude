package com.example.demo.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for PUT /me: the parts of your user you edit yourself. A full
 * replacement, so a blank or missing field clears it. Username and email are
 * not here because Clerk owns them; they change in Clerk's account menu.
 */
public record UpdateMeRequest(
        @Size(max = 100) String nickname,
        @Size(max = 1024) String s3Url) {
}
