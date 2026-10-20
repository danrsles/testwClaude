package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.PersonResult;
import com.example.demo.services.FollowService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finding people to follow. Other users are reachable only like this, as
 * search results carrying public fields; there is still no route to anyone
 * else's full record.
 */
@RestController
@RequestMapping("/users")
public class PeopleController {

    private final FollowService followService;

    public PeopleController(FollowService followService) {
        this.followService = followService;
    }

    /** Users whose name contains q, ignoring case; an empty q finds no one. */
    @GetMapping
    public List<PersonResult> search(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String q) {
        return followService.search(q, Identities.of(jwt));
    }
}
