package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.MarkReadRequest;
import com.example.demo.dto.NotificationResponse;
import com.example.demo.services.NotificationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's notifications. Always the token's user's own: there is
 * no route to anyone else's. The frontend polls GET, so it stays cheap.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return notificationService.mine(Identities.of(jwt)).stream().map(NotificationResponse::from).toList();
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MarkReadRequest request) {
        notificationService.markRead(request.upTo(), Identities.of(jwt));
    }
}
