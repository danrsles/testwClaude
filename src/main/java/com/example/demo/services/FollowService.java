package com.example.demo.services;

import java.util.List;
import java.util.Set;

import com.example.demo.dto.PersonResult;

import com.example.demo.models.Follow;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.FollowRepository;
import com.example.demo.repositories.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Following decides whose wants appear in your list (see WantService.findAll).
 * The follower is always the signed-in user, identified by the token, never an
 * id sent in the request. Following and unfollowing are both idempotent.
 */
@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final NotificationService notificationService;

    public FollowService(FollowRepository followRepository, UserRepository userRepository,
            UserService userService, NotificationService notificationService) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.notificationService = notificationService;
    }

    /**
     * 400 for yourself, 404 for an unknown user; following again changes
     * nothing. A new follow tells the person followed, in the same
     * transaction, so they hear about it once.
     */
    @Transactional
    public void follow(Long followeeId, ClerkIdentity who) {
        User me = userService.current(who);
        if (me.getId().equals(followeeId)) {
            throw new CannotFollowYourselfException();
        }
        if (!userRepository.existsById(followeeId)) {
            throw new UserNotFoundException(followeeId);
        }
        if (followRepository.followIfAbsent(me.getId(), followeeId) == 1) {
            notificationService.notify(followeeId, me, NotificationType.FOLLOW);
        }
    }

    /** Succeeds whether or not you were following them. */
    @Transactional
    public void unfollow(Long followeeId, ClerkIdentity who) {
        followRepository.deleteByFollowerIdAndFolloweeId(userService.current(who).getId(), followeeId);
    }

    /**
     * Your follows, in the order you made them: each one's followee, and
     * whether you hear about their new wants.
     */
    @Transactional
    public List<Follow> following(ClerkIdentity who) {
        return followRepository.findByFollowerIdOrderById(userService.current(who).getId());
    }

    /** The users who follow you, in the order they started, to pick circle members from. */
    @Transactional
    public List<User> followers(ClerkIdentity who) {
        return followRepository.findByFolloweeIdOrderById(userService.current(who).getId()).stream()
                .map(Follow::getFollower)
                .toList();
    }

    /**
     * Stops someone you follow's new wants being announced to you. You still
     * follow them and see their wants. 404 if you don't follow them; muting
     * twice changes nothing.
     */
    @Transactional
    public void mute(Long followeeId, ClerkIdentity who) {
        setNotifyNewWants(followeeId, false, who);
    }

    /**
     * Announces someone you follow's new wants to you again, from the next
     * one. Wants posted while muted are not announced now. 404 if you don't
     * follow them.
     */
    @Transactional
    public void unmute(Long followeeId, ClerkIdentity who) {
        setNotifyNewWants(followeeId, true, who);
    }

    private void setNotifyNewWants(Long followeeId, boolean notify, ClerkIdentity who) {
        if (followRepository.setNotifyNewWants(userService.current(who).getId(), followeeId, notify) == 0) {
            throw new NotFollowingException(followeeId);
        }
    }

    /**
     * Up to 20 users whose name contains the text, ignoring case, each marked
     * with whether you follow them and whether you hear about their new wants. Never includes you. A blank search finds
     * no one rather than listing everybody.
     */
    @Transactional
    public List<PersonResult> search(String text, ClerkIdentity who) {
        User me = userService.current(who);
        String wanted = text == null ? "" : text.strip();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<Long> followed = followRepository.findFolloweeIds(me.getId());
        Set<Long> muted = followRepository.findMutedFolloweeIds(me.getId());
        return userRepository.findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername(wanted, me.getId())
                .stream()
                .map(user -> PersonResult.from(user, followed.contains(user.getId()),
                        followed.contains(user.getId()) && !muted.contains(user.getId())))
                .toList();
    }
}
