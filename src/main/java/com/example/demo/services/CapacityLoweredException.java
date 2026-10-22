package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Capacity can be raised but never lowered, so nobody who has a seat loses it. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CapacityLoweredException extends RuntimeException {

    public CapacityLoweredException(Long wantId, int from, int to) {
        super("Want " + wantId + " takes " + from + "; its capacity cannot be lowered to " + to);
    }
}
