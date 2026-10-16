package com.example.demo.dto;

import com.example.demo.models.Category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body for creating or replacing a want. PUT is a full replacement, so it
 * requires the same fields as POST. No userId: the owner is whoever is signed
 * in, so accepting one would let a client post as somebody else.
 */
public record WantRequest(
        @NotBlank String message,
        @NotNull Category category) {
}
