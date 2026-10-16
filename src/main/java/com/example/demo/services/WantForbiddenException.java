package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Someone tried to change a want that belongs to another user. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class WantForbiddenException extends RuntimeException {

    public WantForbiddenException(Long wantId) {
        super("Want " + wantId + " belongs to another user");
    }
}
