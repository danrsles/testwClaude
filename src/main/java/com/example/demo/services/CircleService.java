package com.example.demo.services;

import java.time.Clock;
import java.util.List;

import com.example.demo.models.Circle;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.CircleRepository;
import com.example.demo.repositories.FollowRepository;
import com.example.demo.repositories.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Circles work like group chats. The owner, who created one, renames it,
 * deletes it, and adds and removes members; members see it and each other,
 * and may leave. Only people who follow the owner can be added, and
 * unfollowing the owner removes you (a cascade in the database).
 *
 * Who may do what: the owner may do anything; a member asking for an owner
 * action gets 403; anyone else, or anyone asking about a deleted circle, gets
 * 404, so outsiders learn nothing.
 *
 * Every change locks the circle's row first (findLiveForUpdate), so changes
 * take turns and none lands on a circle deleted a moment earlier. Wants don't
 * use circles until step 8d.
 */
@Service
public class CircleService {

    private final CircleRepository circleRepository;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final NotificationService notificationService;
    private final Clock clock;

    public CircleService(CircleRepository circleRepository, FollowRepository followRepository,
            UserRepository userRepository, UserService userService, NotificationService notificationService,
            Clock clock) {
        this.circleRepository = circleRepository;
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** Circles you own or are in, not deleted, oldest first. */
    @Transactional
    public List<Circle> mine(ClerkIdentity who) {
        return circleRepository.findMine(userService.current(who).getId());
    }

    /**
     * Creates a circle you own. The name is trimmed; "General" in any case
     * and a duplicate of one of your circles, ignoring case, are 400s.
     */
    @Transactional
    public Circle create(String name, ClerkIdentity who) {
        User me = userService.current(who);
        String trimmed = name.strip();
        requireNotReserved(trimmed);
        // Your circle creates and renames take turns, so two at once can't both pass the check.
        userRepository.lockForCircleNames(me.getId());
        if (circleRepository.existsByOwnerIdAndNameIgnoreCaseAndArchivedAtIsNull(me.getId(), trimmed)) {
            throw new DuplicateCircleNameException(trimmed);
        }
        return circleRepository.save(new Circle(me, trimmed));
    }

    /** Renames a circle you own, by the same rules as create. Tells nobody. */
    @Transactional
    public Circle rename(Long circleId, String name, ClerkIdentity who) {
        User me = userService.current(who);
        Circle circle = requireOwned(circleId, me);
        String trimmed = name.strip();
        requireNotReserved(trimmed);
        userRepository.lockForCircleNames(me.getId());
        if (circleRepository.existsByOwnerIdAndNameIgnoreCaseAndArchivedAtIsNullAndIdNot(me.getId(), trimmed,
                circleId)) {
            throw new DuplicateCircleNameException(trimmed);
        }
        circle.rename(trimmed);
        return circle;
    }

    /**
     * Deletes a circle you own, for everyone: it is archived rather than
     * removed, because wants will point at it. Tells nobody. Deleting it
     * again is a 404, like every route on a deleted circle.
     */
    @Transactional
    public void delete(Long circleId, ClerkIdentity who) {
        requireOwned(circleId, userService.current(who)).archive(clock.instant());
    }

    /** The members, not the owner, in the order they were added. For the owner and members only. */
    @Transactional
    public List<User> members(Long circleId, ClerkIdentity who) {
        User me = userService.current(who);
        Circle circle = circleRepository.findLive(circleId).orElseThrow(() -> new CircleNotFoundException(circleId));
        if (!circle.isOwnedBy(me.getId()) && !circleRepository.isMember(circleId, me.getId())) {
            throw new CircleNotFoundException(circleId);
        }
        return circleRepository.findMembers(circleId);
    }

    /**
     * Adds one of your followers to a circle you own, and tells them
     * (CIRCLE_ADDED). Adding someone already in changes nothing and tells
     * nobody. Someone who doesn't follow you, or doesn't exist, is a 400,
     * decided only once you are known to be the owner.
     */
    @Transactional
    public void addMember(Long circleId, Long userId, ClerkIdentity who) {
        User me = userService.current(who);
        requireOwned(circleId, me);
        // Locked first, so an unfollow either finished before (no row: 400) or waits for this
        // to commit; the insert below can then never break its key to follows.
        if (followRepository.lockFollow(userId, me.getId()) == null) {
            throw new NotYourFollowerException(userId);
        }
        if (circleRepository.addMemberIfAbsent(circleId, me.getId(), userId) == 1) {
            notificationService.notifyAboutCircle(userId, me, NotificationType.CIRCLE_ADDED, circleId);
        }
    }

    /**
     * The owner removes anyone; a member removes only themselves, which is
     * leaving. The owner can't leave (400): they delete the circle instead.
     * Removing someone who isn't in changes nothing. Tells nobody.
     */
    @Transactional
    public void removeMember(Long circleId, Long userId, ClerkIdentity who) {
        User me = userService.current(who);
        Circle circle = circleRepository.findLiveForUpdate(circleId)
                .orElseThrow(() -> new CircleNotFoundException(circleId));
        if (circle.isOwnedBy(me.getId())) {
            if (userId.equals(me.getId())) {
                throw new OwnerCannotLeaveException(circleId);
            }
        } else if (!circleRepository.isMember(circleId, me.getId())) {
            throw new CircleNotFoundException(circleId);
        } else if (!userId.equals(me.getId())) {
            throw new CircleForbiddenException(circleId);
        }
        circleRepository.removeMember(circleId, userId);
    }

    private static void requireNotReserved(String name) {
        if (name.equalsIgnoreCase("General")) {
            throw new ReservedCircleNameException();
        }
    }

    /**
     * Locks the circle's row, then checks you own it: 403 for a member, 404
     * for anyone else, or if it doesn't exist or was deleted.
     */
    private Circle requireOwned(Long circleId, User me) {
        Circle circle = circleRepository.findLiveForUpdate(circleId)
                .orElseThrow(() -> new CircleNotFoundException(circleId));
        if (circle.isOwnedBy(me.getId())) {
            return circle;
        }
        if (circleRepository.isMember(circleId, me.getId())) {
            throw new CircleForbiddenException(circleId);
        }
        throw new CircleNotFoundException(circleId);
    }
}
