package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.WantRequest;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.WantService;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Returns WantResponse rather than the entity, so other users' private details never leave the server. */
@RestController
@RequestMapping("/wants")
public class WantController {

    private final WantService wantService;

    public WantController(WantService wantService) {
        this.wantService = wantService;
    }

    @GetMapping
    public List<WantResponse> wants(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Category category) {
        ClerkIdentity who = Identities.of(jwt);
        return category == null ? wantService.findAll(who) : wantService.findByCategory(category, who);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WantResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WantRequest request) {
        return wantService.create(request.message(), request.category(), request.capacityOnCreate(),
                Identities.of(jwt));
    }

    @PutMapping("/{id}")
    public WantResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody WantRequest request) {
        return wantService.update(id, request.message(), request.category(), request.capacity(),
                Identities.of(jwt));
    }

    @PostMapping("/{id}/close")
    public WantResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return wantService.close(id, Identities.of(jwt));
    }

    @PostMapping("/{id}/reopen")
    public WantResponse reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return wantService.reopen(id, Identities.of(jwt));
    }

    /** Cancels rather than deletes: see WantService.cancel. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        wantService.cancel(id, Identities.of(jwt));
    }
}
