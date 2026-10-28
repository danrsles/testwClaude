package com.example.demo.dto;

import com.example.demo.models.Circle;

/** A circle as its owner and members see it: the owner by public fields only, never the User entity. */
public record CircleResponse(Long id, String name, UserSummary owner) {

    public static CircleResponse from(Circle circle) {
        return new CircleResponse(circle.getId(), circle.getName(), UserSummary.from(circle.getOwner()));
    }
}
