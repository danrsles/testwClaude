package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The want is closed, so its lineup cannot change. */
@ResponseStatus(HttpStatus.CONFLICT)
public class WantClosedException extends RuntimeException {

    public WantClosedException(Long wantId) {
        super("Want " + wantId + " is closed");
    }
}
