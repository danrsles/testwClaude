package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A profile would break a uniqueness rule: the user already has one, or the
 * email belongs to another profile. Checked in the service so the client gets
 * a 409 rather than a 500 from the database constraint.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class UserProfileConflictException extends RuntimeException {

    public UserProfileConflictException(String message) {
        super(message);
    }
}
