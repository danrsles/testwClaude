package com.example.demo.dto;

import java.time.Instant;

import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;

/**
 * A notification as its recipient sees it. The actor is another user, so they
 * appear as a UserSummary, by public fields only, never as the User entity.
 * wantId and wantMessage are null for a follow; for a want, the message is
 * the one it had when the notification was written. previousMessage is set
 * only when the owner renamed the want: the name it replaced.
 */
public record NotificationResponse(Long id, NotificationType type, UserSummary actor, Instant createdAt,
        boolean read, Long wantId, String wantMessage, String previousMessage) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getType(),
                UserSummary.from(notification.getActor()), notification.getCreatedAt(),
                notification.getReadAt() != null, notification.getWantId(), notification.getWantMessage(),
                notification.getPreviousMessage());
    }
}
