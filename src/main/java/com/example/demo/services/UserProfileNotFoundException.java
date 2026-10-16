package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The signed-in user has no profile yet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserProfileNotFoundException extends RuntimeException {

    public UserProfileNotFoundException() {
        super("You have no profile yet");
    }
}
