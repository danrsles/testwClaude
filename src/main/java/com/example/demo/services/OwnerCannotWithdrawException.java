package com.example.demo.services;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The owner's seat comes with the want; giving it up would be cancelling the want. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OwnerCannotWithdrawException extends RuntimeException {

    public OwnerCannotWithdrawException(Long wantId) {
        super("The owner of want " + wantId + " cannot withdraw from it");
    }
}
