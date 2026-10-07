package com.example.demo.controllers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body for creating a profile. s3Url is optional until avatar uploads exist. */
public record CreateUserProfileRequest(
        @NotNull Long userId,
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String nickname,
        @Size(max = 1024) String s3Url) {
}
