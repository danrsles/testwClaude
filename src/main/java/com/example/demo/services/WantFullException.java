package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Every seat on the want is taken. */
@ResponseStatus(HttpStatus.CONFLICT)
public class WantFullException extends RuntimeException {

    public WantFullException(Long wantId) {
        super("Want " + wantId + " is full");
    }
}
