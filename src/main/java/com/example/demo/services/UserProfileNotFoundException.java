package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserProfileNotFoundException extends RuntimeException {

    public UserProfileNotFoundException(Long profileId) {
        super("No user profile with id " + profileId);
    }
}
