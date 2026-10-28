package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.PersonResult;
import com.example.demo.services.FollowService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's follows. The path names whom to follow; the follower is
 * always the token's user. PUT rather than POST because following is
 * idempotent: doing it twice leaves one follow.
 */
@RestController
@RequestMapping("/follows")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /** Who you follow, by public fields, each with whether you hear about their new wants. */
    @GetMapping
    public List<PersonResult> following(@AuthenticationPrincipal Jwt jwt) {
        return followService.following(Identities.of(jwt)).stream()
                .map(f -> PersonResult.from(f.getFollowee(), true, f.isNotifyNewWants()))
                .toList();
    }

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long userId) {
        followService.follow(userId, Identities.of(jwt));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable Long userId) {
        followService.unfollow(userId, Identities.of(jwt));
    }

    /** Hear about their new wants again: PUT, like following, puts the setting back on. */
    @PutMapping("/{userId}/notify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long userId) {
        followService.unmute(userId, Identities.of(jwt));
    }

    /** Mute their new wants, while still following them. */
    @DeleteMapping("/{userId}/notify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long userId) {
        followService.mute(userId, Identities.of(jwt));
    }
}
