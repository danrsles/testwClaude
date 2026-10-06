package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class WantNotFoundException extends RuntimeException {

    public WantNotFoundException(Long wantId) {
        super("No want with id " + wantId);
    }
}
