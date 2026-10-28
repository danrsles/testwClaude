package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The owner of a circle deletes it rather than leaving. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OwnerCannotLeaveException extends RuntimeException {

    public OwnerCannotLeaveException(Long circleId) {
        super("The owner cannot leave circle " + circleId + "; delete it instead");
    }
}
