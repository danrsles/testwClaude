package com.example.demo.controllers;

import com.example.demo.models.User;
import com.example.demo.services.UserService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** The signed-in user, created on first call. The UI uses its id to tell which wants are its own. */
    @GetMapping("/me")
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return userService.current(Identities.of(jwt));
    }
}
