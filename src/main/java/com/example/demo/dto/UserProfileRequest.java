package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or replacing the signed-in user's profile. No userId: the
 * owner is whoever is signed in. s3Url is optional until avatar uploads exist.
 */
public record UserProfileRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String nickname,
        @Size(max = 1024) String s3Url) {
}
