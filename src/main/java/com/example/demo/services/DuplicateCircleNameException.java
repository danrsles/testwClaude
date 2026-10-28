package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** You already have a circle by that name, ignoring case. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class DuplicateCircleNameException extends RuntimeException {

    public DuplicateCircleNameException(String name) {
        super("You already have a circle called " + name);
    }
}
