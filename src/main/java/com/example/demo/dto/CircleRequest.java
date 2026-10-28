package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /circles and PUT /circles/{id}. CircleService trims it and refuses "General" and duplicates. */
public record CircleRequest(@NotBlank @Size(max = 40) String name) {
}
