package com.example.demo.controllers;

import com.example.demo.dto.UpdateMeRequest;
import com.example.demo.models.User;
import com.example.demo.services.UserService;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user: their account and profile in one. Returns the entity,
 * private fields included, which is safe only because it is always the
 * caller's own row. There is no route to anyone else's user.
 */
@RestController
@RequestMapping("/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Created on first call. */
    @GetMapping
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return userService.current(Identities.of(jwt));
    }

    @PutMapping
    public User update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateMeRequest request) {
        return userService.updateMe(Identities.of(jwt), request.nickname(), request.s3Url());
    }
}
