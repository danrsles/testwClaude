package com.example.demo.controllers;

import com.example.demo.models.Category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWantRequest(
        @NotBlank String message,
        @NotNull Category category,
        @NotNull Long userId) {
}
