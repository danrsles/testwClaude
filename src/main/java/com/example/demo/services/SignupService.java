package com.example.demo.services;

import com.example.demo.dto.SignupEventDetails;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking and giving up seats on other people's wants. The signed-in user is
 * always the one signing up, identified by the token, never an id sent in the
 * request. Both are idempotent.
 */
@Service
public class SignupService {

    private final WantRepository wantRepository;
    private final WantSignupRepository wantSignupRepository;
    private final UserService userService;
    private final NotificationService notificationService;

    public SignupService(WantRepository wantRepository, WantSignupRepository wantSignupRepository,
            UserService userService, NotificationService notificationService) {
        this.wantRepository = wantRepository;
        this.wantSignupRepository = wantSignupRepository;
        this.userService = userService;
        this.notificationService = notificationService;
    }

    @Transactional
    public SignupEventDetails signUp(Long wantId, ClerkIdentity who) {
        throw new UnsupportedOperationException("not built yet");
    }

    @Transactional
    public SignupEventDetails withdraw(Long wantId, ClerkIdentity who) {
        throw new UnsupportedOperationException("not built yet");
    }
}
