package com.example.demo.controllers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for replacing a profile. No userId: a profile cannot change owner, so
 * accepting one would only invite a request that silently ignores it.
 */
public record UpdateUserProfileRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String nickname,
        @Size(max = 1024) String s3Url) {
}
