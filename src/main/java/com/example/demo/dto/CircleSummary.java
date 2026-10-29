package com.example.demo.dto;

import com.example.demo.models.Circle;

/** The circle a want was posted to, as the want's audience sees it: no owner, no members. */
public record CircleSummary(Long id, String name) {

    public static CircleSummary from(Circle circle) {
        return new CircleSummary(circle.getId(), circle.getName());
    }
}
