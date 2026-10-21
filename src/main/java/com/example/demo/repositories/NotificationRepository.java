package com.example.demo.repositories;

import java.util.List;

import com.example.demo.models.Notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** A recipient's newest notifications, newest first. */
    List<Notification> findTop50ByRecipientIdOrderByIdDesc(Long recipientId);

    /**
     * Native, like FollowRepository.followIfAbsent, so the database fills in
     * created_at and nothing has to load the recipient first.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, actor_id, type) VALUES (:recipientId, :actorId, :type)",
            nativeQuery = true)
    void notify(@Param("recipientId") Long recipientId, @Param("actorId") Long actorId, @Param("type") String type);

    /**
     * Marks a recipient's notifications read, up to and including an id. The
     * bound is what the recipient was shown, so one that arrived since stays
     * unread. Already-read rows keep the time they were first read.
     */
    @Modifying
    @Query(value = "UPDATE notifications SET read_at = now() "
            + "WHERE recipient_id = :recipientId AND id <= :upTo AND read_at IS NULL", nativeQuery = true)
    int markRead(@Param("recipientId") Long recipientId, @Param("upTo") Long upTo);
}
