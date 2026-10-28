package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.CircleRequest;
import com.example.demo.dto.CircleResponse;
import com.example.demo.dto.UserSummary;
import com.example.demo.services.CircleService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Circles, which work like group chats: the owner runs one, members see it
 * and each other. Who may do what is decided in CircleService; this only
 * maps to response records, so no User entity leaves for another user.
 */
@RestController
@RequestMapping("/circles")
public class CircleController {

    private final CircleService circleService;

    public CircleController(CircleService circleService) {
        this.circleService = circleService;
    }

    /** Circles you own or are in. */
    @GetMapping
    public List<CircleResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return circleService.mine(Identities.of(jwt)).stream().map(CircleResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CircleResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CircleRequest request) {
        return CircleResponse.from(circleService.create(request.name(), Identities.of(jwt)));
    }

    @PutMapping("/{id}")
    public CircleResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody CircleRequest request) {
        return CircleResponse.from(circleService.rename(id, request.name(), Identities.of(jwt)));
    }

    /** Deletes the circle for everyone; the row is archived, because wants will point at it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        circleService.delete(id, Identities.of(jwt));
    }

    /** The members, not the owner, by public fields; for the owner and members only. */
    @GetMapping("/{id}/members")
    public List<UserSummary> members(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return circleService.members(id, Identities.of(jwt)).stream().map(UserSummary::from).toList();
    }

    @PutMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long userId) {
        circleService.addMember(id, userId, Identities.of(jwt));
    }

    /** The owner removing someone, or a member removing themselves, which is leaving. */
    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long userId) {
        circleService.removeMember(id, userId, Identities.of(jwt));
    }
}
