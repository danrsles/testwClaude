package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The want has ended, which is final, so nothing about it can change. */
@ResponseStatus(HttpStatus.CONFLICT)
public class WantEndedException extends RuntimeException {

    public WantEndedException(Long wantId) {
        super("Want " + wantId + " has ended");
    }
}
