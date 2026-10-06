package com.example.demo.controllers;

import com.example.demo.models.Category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body for creating or replacing a want. PUT is a full replacement, so it
 * requires the same fields as POST.
 */
public record WantRequest(
        @NotBlank String message,
        @NotNull Category category,
        @NotNull Long userId) {
}
