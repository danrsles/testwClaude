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
     * As notify, about a want. The database copies the want's current message
     * onto the row, so the notification still reads correctly after an edit.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, actor_id, type, want_id, want_message) "
            + "SELECT :recipientId, :actorId, :type, w.id, w.message FROM wants w WHERE w.id = :wantId",
            nativeQuery = true)
    void notifyAboutWant(@Param("recipientId") Long recipientId, @Param("actorId") Long actorId,
            @Param("type") String type, @Param("wantId") Long wantId);

    /**
     * Tells everyone signed up for a want, except its owner, in one statement,
     * so either the whole lineup hears or, if the change rolls back, nobody
     * does. previousMessage is cast because PostgreSQL cannot infer a type
     * for a null parameter.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, actor_id, type, want_id, want_message, previous_message) "
            + "SELECT s.user_id, :actorId, :type, w.id, w.message, CAST(:previousMessage AS VARCHAR) "
            + "FROM want_signups s JOIN wants w ON w.id = s.want_id "
            + "WHERE s.want_id = :wantId AND s.user_id <> w.user_id", nativeQuery = true)
    void notifyLineup(@Param("wantId") Long wantId, @Param("actorId") Long actorId, @Param("type") String type,
            @Param("previousMessage") String previousMessage);

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
