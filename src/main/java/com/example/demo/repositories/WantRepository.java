package com.example.demo.repositories;

import java.util.List;
import java.util.Optional;

import com.example.demo.models.Category;
import com.example.demo.models.Want;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WantRepository extends JpaRepository<Want, Long> {

    /**
     * Who can see a want, shared by the feed, the category feed and isInFeed
     * (so signups and withdrawals too). Needs the want as w, its circle
     * left-joined as c, and :userId. A user sees a want, unless it is
     * cancelled, when:
     * - they posted it; or
     * - it has no circle (General) and they follow the poster; or
     * - its circle is not deleted, they own it or are in it, and the poster
     *   still owns it or is in it, so removing someone hides their wants.
     * NotificationRepository.announce and announceToCircle repeat this in
     * SQL; WantAnnounceIT checks that everyone they tell can see the want.
     */
    String VISIBLE_TO_USER = """
            w.cancelledAt is null
              and (w.user.id = :userId
                   or (c is null
                       and w.user.id in (select f.followee.id from Follow f where f.follower.id = :userId))
                   or (c is not null and c.archivedAt is null
                       and (c.owner.id = :userId
                            or exists (select m from CircleMember m
                                       where m.id.circleId = c.id and m.id.memberId = :userId))
                       and (c.owner.id = w.user.id
                            or exists (select p from CircleMember p
                                       where p.id.circleId = c.id and p.id.memberId = w.user.id))))""";

    /**
     * A user's feed, by VISIBLE_TO_USER: their own wants, General wants of
     * everyone they follow, and wants in circles they own or are in. The
     * circle is fetched in the same query, for its name in the response.
     */
    @Query("select w from Want w left join fetch w.circle c where " + VISIBLE_TO_USER + " order by w.id")
    List<Want> findFeed(@Param("userId") Long userId);

    /** As findFeed, narrowed to one category. */
    @Query("select w from Want w left join fetch w.circle c where w.category = :category and "
            + VISIBLE_TO_USER + " order by w.id")
    List<Want> findFeedByCategory(@Param("userId") Long userId, @Param("category") Category category);

    /** Whether a want is in a user's feed, by VISIBLE_TO_USER. */
    @Query("select case when count(w) > 0 then true else false end from Want w left join w.circle c "
            + "where w.id = :wantId and " + VISIBLE_TO_USER)
    boolean isInFeed(@Param("wantId") Long wantId, @Param("userId") Long userId);

    /**
     * Loads a want with SELECT ... FOR UPDATE, holding its row lock until the
     * transaction ends. Every change to a want or its lineup starts here, so
     * two signups for the last seat take turns: the second waits, then counts
     * the seats in a new statement that sees the first one's committed signup.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Want w where w.id = :id")
    Optional<Want> findByIdForUpdate(@Param("id") Long id);

    /**
     * Closes every want whose deadline has passed and tells everyone seated
     * on it, its owner included, in one statement: the UPDATE hands the wants
     * it closed to the INSERT. closed_at is the database's now(), when this
     * ran, so a late run shows as late. Returns the notifications written.
     *
     * The guard is what makes a repeat or a second runner harmless. Under
     * READ COMMITTED, an UPDATE that finds a row locked waits, then checks
     * the WHERE again against the row as committed: if a reopen cleared the
     * deadline, or another run already set closed_at, it skips the row.
     */
    @Modifying
    @Query(value = """
            WITH expired AS (
                UPDATE wants SET closed_at = now()
                WHERE closed_at IS NULL AND cancelled_at IS NULL AND ended_at IS NULL AND expires_at <= now()
                RETURNING id, user_id, message)
            INSERT INTO notifications (recipient_id, actor_id, type, want_id, want_message)
            SELECT s.user_id, e.user_id, 'WANT_EXPIRED', e.id, e.message
            FROM expired e JOIN want_signups s ON s.want_id = e.id""", nativeQuery = true)
    int expireDue();
}
