package com.example.demo.services;

import java.util.List;

import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.NotificationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's letterbox. Other services write to it as part of the
 * action that caused the notification, in the same transaction, so a follow
 * and its notification are saved together or not at all.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserService userService;

    public NotificationService(NotificationRepository notificationRepository, UserService userService) {
        this.notificationRepository = notificationRepository;
        this.userService = userService;
    }

    /** Tells the recipient the actor did something. Joins the caller's transaction. */
    @Transactional
    public void notify(Long recipientId, User actor, NotificationType type) {
        notificationRepository.notify(recipientId, actor.getId(), type.name());
    }

    /** As notify, about a want, whose current message the notification keeps. Joins the caller's transaction. */
    @Transactional
    public void notify(Long recipientId, User actor, NotificationType type, Long wantId) {
        notificationRepository.notifyAboutWant(recipientId, actor.getId(), type.name(), wantId);
    }

    /** As notify, about a circle, whose current name the notification keeps. Joins the caller's transaction. */
    @Transactional
    public void notifyAboutCircle(Long recipientId, User actor, NotificationType type, Long circleId) {
        notificationRepository.notifyAboutCircle(recipientId, actor.getId(), type.name(), circleId);
    }

    /**
     * Tells a want's lineup, all but its owner, that the actor did something
     * to it. previousMessage is the name it replaced, or null. Joins the
     * caller's transaction.
     */
    @Transactional
    public void notifyLineup(Long wantId, User actor, NotificationType type, String previousMessage) {
        notificationRepository.notifyLineup(wantId, actor.getId(), type.name(), previousMessage);
    }

    /**
     * Tells the actor's followers about their want, except anyone who muted
     * them, is in its lineup or was told about it before. The caller decides
     * whether the want is worth announcing. Joins the caller's transaction.
     */
    @Transactional
    public void announce(Long wantId, User actor) {
        notificationRepository.announce(wantId, actor.getId());
    }

    /** Your 50 newest notifications, newest first, read or not. */
    @Transactional
    public List<Notification> mine(ClerkIdentity who) {
        return notificationRepository.findTop50ByRecipientIdOrderByIdDesc(userService.current(who).getId());
    }

    /**
     * Marks yours read up to and including an id, the newest you were shown,
     * so one that arrived meanwhile stays unread. Never touches anyone else's.
     */
    @Transactional
    public void markRead(Long upTo, ClerkIdentity who) {
        notificationRepository.markRead(userService.current(who).getId(), upTo);
    }
}
