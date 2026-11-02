package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.ReopenRequest;
import com.example.demo.dto.WantRequest;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.services.WantService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

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

    /** The feed's page size when the request names none, and the most one may ask for. */
    static final int PAGE = 20;

    static final int MAX_PAGE = 50;

    private final WantService wantService;

    public WantController(WantService wantService) {
        this.wantService = wantService;
    }

    /**
     * A page of your feed, newest first. before is the id of the last want
     * you already have; left out, the page starts at the newest. A page
     * shorter than limit is the last. A limit outside 1 to MAX_PAGE is a 400.
     */
    @GetMapping
    public List<WantResponse> wants(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) @Positive Long before,
            @RequestParam(defaultValue = "" + PAGE) @Min(1) @Max(MAX_PAGE) int limit) {
        return wantService.findPage(Identities.of(jwt), category, before, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WantResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WantRequest request) {
        return wantService.create(request.message(), request.category(), request.capacityOnCreate(),
                request.expiresAt(), request.circleId(), Identities.of(jwt));
    }

    @PutMapping("/{id}")
    public WantResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody WantRequest request) {
        return wantService.update(id, request.message(), request.category(), request.capacity(),
                request.openInvite(), request.expiresAt(), request.noDeadline(), request.circleId(),
                request.allFollowers(), Identities.of(jwt));
    }

    @PostMapping("/{id}/close")
    public WantResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return wantService.close(id, Identities.of(jwt));
    }

    @PostMapping("/{id}/reopen")
    public WantResponse reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody(required = false) ReopenRequest request) {
        return wantService.reopen(id, request == null ? null : request.expiresAt(), Identities.of(jwt));
    }

    @PostMapping("/{id}/start")
    public WantResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return wantService.start(id, Identities.of(jwt));
    }

    @PostMapping("/{id}/end")
    public WantResponse end(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return wantService.end(id, Identities.of(jwt));
    }

    /** Cancels rather than deletes: see WantService.cancel. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        wantService.cancel(id, Identities.of(jwt));
    }
}
