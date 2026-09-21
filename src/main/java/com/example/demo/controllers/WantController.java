package com.example.demo.controllers;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.Want;
import com.example.demo.services.WantService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wants")
public class WantController {

    private final WantService wantService;

    public WantController(WantService wantService) {
        this.wantService = wantService;
    }

    @GetMapping
    public List<Want> wants(@RequestParam(required = false) Category category) {
        return category == null ? wantService.findAll() : wantService.findByCategory(category);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Want create(@Valid @RequestBody CreateWantRequest request) {
        return wantService.create(request.message(), request.category(), request.userId());
    }
}
