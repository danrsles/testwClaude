package com.example.demo.controllers;

import java.util.List;

import com.example.demo.models.UserProfile;
import com.example.demo.services.UserProfileService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/profiles")
public class UserProfileController {

    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public List<UserProfile> profiles(@RequestParam(required = false) Long userId) {
        return userId == null ? profileService.findAll() : profileService.findByUserId(userId);
    }

    @GetMapping("/{id}")
    public UserProfile profile(@PathVariable Long id) {
        return profileService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfile create(@Valid @RequestBody CreateUserProfileRequest request) {
        return profileService.create(request.userId(), request.email(), request.nickname(), request.s3Url());
    }

    @PutMapping("/{id}")
    public UserProfile update(@PathVariable Long id, @Valid @RequestBody UpdateUserProfileRequest request) {
        return profileService.update(id, request.email(), request.nickname(), request.s3Url());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        profileService.delete(id);
    }
}
