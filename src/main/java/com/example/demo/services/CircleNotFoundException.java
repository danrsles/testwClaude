package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A circle that doesn't exist, was deleted, or that you neither own nor are in. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CircleNotFoundException extends RuntimeException {

    public CircleNotFoundException(Long circleId) {
        super("No circle with id " + circleId);
    }
}
