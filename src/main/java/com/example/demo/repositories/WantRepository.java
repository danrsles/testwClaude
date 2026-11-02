package com.example.demo.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.example.demo.models.Category;
import com.example.demo.models.Want;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
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
     * The start of both feed queries. Want.user and Circle.owner are eager,
     * so anything not fetched here costs a query of its own per owner after
     * the list arrives: N+1, growing with the people you follow
     * (WantFeedQueriesIT). The circle is fetched for its name in the
     * response. Each is a single row per want, so the joins never multiply
     * the rows; lineups, a collection, are WantSignupRepository.findLineups.
     */
    String FEED = "select w from Want w join fetch w.user left join fetch w.circle c left join fetch c.owner ";

    /**
     * Where a feed's wants can come from: posted by :authorIds (you and
     * everyone you follow, findFolloweeIds) or in one of :circleIds (yours,
     * findCircleIds). Every want VISIBLE_TO_USER lets through matches it, and
     * a few it refuses do too, so it never changes the result.
     *
     * It is there for the indexes. VISIBLE_TO_USER is an OR across wants,
     * follows and circles, which PostgreSQL can only answer by reading every
     * want and testing each: 490 ms at a million wants. This OR is of two
     * columns of wants against plain lists, so it reads wants_user_idx and
     * wants_circle_idx and touches only the rows it needs: about 1 ms. The
     * lists are bound as values on purpose: as subqueries, the planner goes
     * back to reading the table. Want.userId and Want.circleId are the
     * columns of wants themselves; w.user.id would be the fetched users
     * row's id, which no index on wants serves.
     */
    String FEED_SOURCES = "(w.userId in :authorIds or w.circleId in :circleIds) and ";

    /**
     * A page of a user's feed, by VISIBLE_TO_USER: their own wants, General
     * wants of everyone they follow, and wants in circles they own or are in.
     * authorIds and circleIds are FEED_SOURCES.
     *
     * Newest first, and only wants older than :before, the id of the last
     * want of the previous page (keyset paging). A want posted meanwhile
     * lands above the pages already loaded instead of pushing a row from one
     * page onto the next, as an offset would. limit is the page size.
     */
    @Query(FEED + "where w.id < :before and " + FEED_SOURCES + VISIBLE_TO_USER + " order by w.id desc")
    List<Want> findFeed(@Param("userId") Long userId, @Param("authorIds") Collection<Long> authorIds,
            @Param("circleIds") Collection<Long> circleIds, @Param("before") Long before, Limit limit);

    /** As findFeed, narrowed to one category. */
    @Query(FEED + "where w.id < :before and w.category = :category and " + FEED_SOURCES + VISIBLE_TO_USER
            + " order by w.id desc")
    List<Want> findFeedByCategory(@Param("userId") Long userId, @Param("authorIds") Collection<Long> authorIds,
            @Param("circleIds") Collection<Long> circleIds, @Param("category") Category category,
            @Param("before") Long before, Limit limit);

    /** The ids of everyone a user follows, for FEED_SOURCES. */
    @Query("select f.followee.id from Follow f where f.follower.id = :userId")
    List<Long> findFolloweeIds(@Param("userId") Long userId);

    /** The ids of the circles a user owns or is in, not deleted, for FEED_SOURCES. */
    @Query("select c.id from Circle c where c.archivedAt is null and (c.owner.id = :userId "
            + "or exists (select m from CircleMember m where m.id.circleId = c.id and m.id.memberId = :userId))")
    List<Long> findCircleIds(@Param("userId") Long userId);

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
