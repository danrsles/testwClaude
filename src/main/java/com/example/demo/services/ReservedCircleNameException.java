package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** "General" names all your followers on the screen, so no circle may take it. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ReservedCircleNameException extends RuntimeException {

    public ReservedCircleNameException() {
        super("\"General\" is reserved");
    }
}
