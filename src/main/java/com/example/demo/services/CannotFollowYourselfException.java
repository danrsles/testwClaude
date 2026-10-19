package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Your own wants are always in your list, so following yourself means nothing. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CannotFollowYourselfException extends RuntimeException {

    public CannotFollowYourselfException() {
        super("You cannot follow yourself");
    }
}
