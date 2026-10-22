package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The want is cancelled, which is final: nothing about it can change. */
@ResponseStatus(HttpStatus.CONFLICT)
public class WantCancelledException extends RuntimeException {

    public WantCancelledException(Long wantId) {
        super("Want " + wantId + " is cancelled");
    }
}
