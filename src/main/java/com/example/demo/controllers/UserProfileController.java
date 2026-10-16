package com.example.demo.controllers;

import com.example.demo.dto.UserProfileRequest;
import com.example.demo.models.UserProfile;
import com.example.demo.services.UserProfileService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own profile. There are no id-based routes: a profile
 * holds an email address, and "me" leaves no id to swap for someone else's.
 */
@RestController
@RequestMapping("/user/profiles")
public class UserProfileController {

    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    /** 404 until the user has created a profile. */
    @GetMapping("/me")
    public UserProfile mine(@AuthenticationPrincipal Jwt jwt) {
        return profileService.findMine(Identities.of(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfile create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserProfileRequest request) {
        return profileService.create(Identities.of(jwt), request.email(), request.nickname(), request.s3Url());
    }

    @PutMapping("/me")
    public UserProfile update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserProfileRequest request) {
        return profileService.update(Identities.of(jwt), request.email(), request.nickname(), request.s3Url());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        profileService.delete(Identities.of(jwt));
    }
}
