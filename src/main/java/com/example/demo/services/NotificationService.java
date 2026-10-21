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
