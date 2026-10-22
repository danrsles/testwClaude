package com.example.demo.controllers;

import com.example.demo.dto.SignupEventDetails;
import com.example.demo.services.SignupService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your seat on a want. The path names the want; the person signing up is
 * always the token's user. Both routes answer with the lineup as it now stands.
 */
@RestController
@RequestMapping("/wants/{id}/signups")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping
    public SignupEventDetails signUp(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return signupService.signUp(id, Identities.of(jwt));
    }

    @DeleteMapping
    public SignupEventDetails withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return signupService.withdraw(id, Identities.of(jwt));
    }
}
