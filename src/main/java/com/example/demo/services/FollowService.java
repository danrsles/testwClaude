package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Follow;
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

    public FollowService(FollowRepository followRepository, UserRepository userRepository,
            UserService userService) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    /** 400 for yourself, 404 for an unknown user; following again changes nothing. */
    @Transactional
    public void follow(Long followeeId, ClerkIdentity who) {
        User me = userService.current(who);
        if (me.getId().equals(followeeId)) {
            throw new CannotFollowYourselfException();
        }
        if (!userRepository.existsById(followeeId)) {
            throw new UserNotFoundException(followeeId);
        }
        followRepository.followIfAbsent(me.getId(), followeeId);
    }

    /** Succeeds whether or not you were following them. */
    @Transactional
    public void unfollow(Long followeeId, ClerkIdentity who) {
        followRepository.deleteByFollowerIdAndFolloweeId(userService.current(who).getId(), followeeId);
    }

    /** The users you follow, in the order you followed them. */
    @Transactional
    public List<User> following(ClerkIdentity who) {
        return followRepository.findByFollowerIdOrderById(userService.current(who).getId()).stream()
                .map(Follow::getFollowee)
                .toList();
    }
}
