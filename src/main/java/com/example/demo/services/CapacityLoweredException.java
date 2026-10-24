package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Capacity can be raised but never lowered, so nobody who has a seat loses
 * it. An open invite has no limit, so giving it any limit is lowering it.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CapacityLoweredException extends RuntimeException {

    /** from is null for an open invite. */
    public CapacityLoweredException(Long wantId, Integer from, int to) {
        super("Want " + wantId + " takes " + (from == null ? "everyone" : from)
                + "; its capacity cannot be lowered to " + to);
    }
}
