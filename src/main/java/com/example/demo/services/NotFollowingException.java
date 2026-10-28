package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Muting or unmuting someone you don't follow: there is no follow to change. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFollowingException extends RuntimeException {

    public NotFollowingException(Long followeeId) {
        super("You do not follow user " + followeeId);
    }
}
