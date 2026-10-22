package com.example.demo.services;

import java.util.List;

import com.example.demo.dto.SignupEventDetails;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking and giving up seats on other people's wants. The signed-in user is
 * always the one signing up, identified by the token, never an id sent in the
 * request. Both are idempotent.
 *
 * Each starts by locking the want's row (WantRepository.findByIdForUpdate),
 * so two people after the last seat take turns: the second waits until the
 * first commits, then counts the seats afresh and finds the want full.
 * Counting without the lock would let both see a free seat and both get in.
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

    /**
     * Takes a seat and tells the owner. Already holding one, as the owner
     * always does, changes nothing, even on a full want. 404 for a want that
     * is not in your feed, 409 when every seat is taken.
     */
    @Transactional
    public SignupEventDetails signUp(Long wantId, ClerkIdentity who) {
        User me = userService.current(who);
        Want want = lock(wantId);

        // Before the other checks, so repeating a signup is never an error.
        if (wantSignupRepository.existsByWantIdAndUserId(wantId, me.getId())) {
            return details(want, me);
        }
        if (!wantRepository.isInFeed(wantId, me.getId())) {
            throw new WantNotFoundException(wantId);
        }
        // A separate statement after the lock, so it sees every committed signup.
        if (wantSignupRepository.countByWantId(wantId) >= want.getCapacity()) {
            throw new WantFullException(wantId);
        }
        if (wantSignupRepository.signUpIfAbsent(wantId, me.getId()) == 1) {
            notificationService.notify(want.getUser().getId(), me, NotificationType.WANT_SIGNUP, wantId);
        }
        return details(want, me);
    }

    /**
     * Gives up your seat and tells the owner; not holding one changes
     * nothing. The owner cannot withdraw (400): that would be cancelling. A
     * member can always withdraw, even after unfollowing the owner; anyone
     * else gets 404 for a want that is not in their feed.
     */
    @Transactional
    public SignupEventDetails withdraw(Long wantId, ClerkIdentity who) {
        User me = userService.current(who);
        Want want = lock(wantId);

        if (want.getUser().getId().equals(me.getId())) {
            throw new OwnerCannotWithdrawException(wantId);
        }
        if (wantSignupRepository.withdraw(wantId, me.getId()) == 1) {
            notificationService.notify(want.getUser().getId(), me, NotificationType.WANT_WITHDRAWN, wantId);
        } else if (!wantRepository.isInFeed(wantId, me.getId())) {
            throw new WantNotFoundException(wantId);
        }
        return details(want, me);
    }

    private Want lock(Long wantId) {
        return wantRepository.findByIdForUpdate(wantId).orElseThrow(() -> new WantNotFoundException(wantId));
    }

    private SignupEventDetails details(Want want, User me) {
        List<String> lineup = wantSignupRepository.findLineupUsernames(want.getId());
        return new SignupEventDetails(want.getId(), want.getMessage(), want.getCapacity(), lineup.size(), lineup,
                lineup.contains(me.getUsername()));
    }
}
