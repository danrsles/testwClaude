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
     * As notify, about a circle. The database copies the circle's current
     * name onto the row, so the notification still reads correctly after a
     * rename.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, actor_id, type, circle_id, circle_name) "
            + "SELECT :recipientId, :actorId, :type, c.id, c.name FROM circles c WHERE c.id = :circleId",
            nativeQuery = true)
    void notifyAboutCircle(@Param("recipientId") Long recipientId, @Param("actorId") Long actorId,
            @Param("type") String type, @Param("circleId") Long circleId);

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
     * Tells the owner's followers about a want (WANT_POSTED), in one
     * statement, except those who muted the owner, those already in its
     * lineup and those told before: ON CONFLICT against
     * notifications_posted_once skips them, so this can run after every
     * create and edit. Who is told repeats the feed's rule
     * (WantRepository.isInFeed) in SQL; WantAnnounceIT checks the two agree.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (recipient_id, actor_id, type, want_id, want_message) "
            + "SELECT f.follower_id, :actorId, 'WANT_POSTED', w.id, w.message "
            + "FROM wants w JOIN follows f ON f.followee_id = w.user_id "
            + "WHERE w.id = :wantId AND w.cancelled_at IS NULL AND f.notify_new_wants "
            + "AND NOT EXISTS (SELECT 1 FROM want_signups s WHERE s.want_id = w.id AND s.user_id = f.follower_id) "
            + "ON CONFLICT (want_id, recipient_id) WHERE type = 'WANT_POSTED' DO NOTHING", nativeQuery = true)
    void announce(@Param("wantId") Long wantId, @Param("actorId") Long actorId);

    /**
     * As announce, for a want posted to a circle: its owner and members,
     * except the poster, the lineup and anyone told before. Muting doesn't
     * apply: following has nothing to do with being in a circle. Carries the
     * circle's id and name, so a member who doesn't follow the poster sees
     * why they were told. The poster must still be in the circle and the
     * circle not deleted, as for seeing the want (WantRepository.isInFeed);
     * WantAnnounceIT checks the two agree.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications "
            + "(recipient_id, actor_id, type, want_id, want_message, circle_id, circle_name) "
            + "SELECT r.recipient_id, :actorId, 'WANT_POSTED', w.id, w.message, c.id, c.name "
            + "FROM wants w JOIN circles c ON c.id = w.circle_id "
            + "JOIN (SELECT c2.owner_id AS recipient_id, c2.id AS circle_id FROM circles c2 "
            + "      UNION SELECT m.member_id, m.circle_id FROM circle_members m) r ON r.circle_id = c.id "
            + "WHERE w.id = :wantId AND w.cancelled_at IS NULL AND c.archived_at IS NULL "
            + "AND r.recipient_id <> w.user_id "
            + "AND (c.owner_id = w.user_id OR EXISTS "
            + "     (SELECT 1 FROM circle_members p WHERE p.circle_id = c.id AND p.member_id = w.user_id)) "
            + "AND NOT EXISTS (SELECT 1 FROM want_signups s WHERE s.want_id = w.id AND s.user_id = r.recipient_id) "
            + "ON CONFLICT (want_id, recipient_id) WHERE type = 'WANT_POSTED' DO NOTHING", nativeQuery = true)
    void announceToCircle(@Param("wantId") Long wantId, @Param("actorId") Long actorId);

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
