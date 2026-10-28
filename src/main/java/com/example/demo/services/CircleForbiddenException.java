package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A member asking for something only the circle's owner may do. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CircleForbiddenException extends RuntimeException {

    public CircleForbiddenException(Long circleId) {
        super("Only the owner of circle " + circleId + " may do that");
    }
}
