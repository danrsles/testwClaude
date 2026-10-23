package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a want that is happening can be ended. */
@ResponseStatus(HttpStatus.CONFLICT)
public class WantNotHappeningException extends RuntimeException {

    public WantNotHappeningException(Long wantId) {
        super("Want " + wantId + " is not happening");
    }
}
