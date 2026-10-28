package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.UserSummary;
import com.example.demo.services.FollowService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people who follow you, by public fields: who a circle's owner can add.
 * All of them at once; the screen filters them by name.
 */
@RestController
@RequestMapping("/followers")
public class FollowerController {

    private final FollowService followService;

    public FollowerController(FollowService followService) {
        this.followService = followService;
    }

    @GetMapping
    public List<UserSummary> followers(@AuthenticationPrincipal Jwt jwt) {
        return followService.followers(Identities.of(jwt)).stream().map(UserSummary::from).toList();
    }
}
