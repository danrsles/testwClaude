package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only people who follow a circle's owner can be added to it. Also covers a user who doesn't exist. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class NotYourFollowerException extends RuntimeException {

    public NotYourFollowerException(Long userId) {
        super("User " + userId + " does not follow you");
    }
}
