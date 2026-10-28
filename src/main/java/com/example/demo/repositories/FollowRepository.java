package com.example.demo.repositories;

import java.util.List;
import java.util.Set;

import com.example.demo.models.Follow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    List<Follow> findByFollowerIdOrderById(Long followerId);

    /** Who follows a user, in the order they started. */
    List<Follow> findByFolloweeIdOrderById(Long followeeId);

    /**
     * Locks a follow row with FOR KEY SHARE, so it can't be deleted until the
     * transaction ends, and returns 1, or null if there is no such follow.
     * CircleService takes it before adding a member: an unfollow that got
     * there first has deleted the row, and one that comes later waits, so
     * the insert into circle_members never breaks its key to follows.
     */
    @Query(value = "SELECT 1 FROM follows WHERE follower_id = :followerId AND followee_id = :followeeId "
            + "FOR KEY SHARE", nativeQuery = true)
    Integer lockFollow(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    void deleteByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    /** The ids of everyone a user follows, for marking search results. */
    @Query("select f.followee.id from Follow f where f.follower.id = :followerId")
    Set<Long> findFolloweeIds(@Param("followerId") Long followerId);

    /** The ids of everyone a user follows but has muted, for marking search results. */
    @Query("select f.followee.id from Follow f where f.follower.id = :followerId and f.notifyNewWants = false")
    Set<Long> findMutedFolloweeIds(@Param("followerId") Long followerId);

    /**
     * Mutes (false) or unmutes (true) a followee's new wants. Returns the
     * rows matched: 0 means the follower doesn't follow them. A repeat still
     * matches its row, so it returns 1 and changes nothing.
     */
    @Modifying
    @Query("update Follow f set f.notifyNewWants = :notify "
            + "where f.follower.id = :followerId and f.followee.id = :followeeId")
    int setNotifyNewWants(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId,
            @Param("notify") boolean notify);

    /**
     * Follows unless already following. ON CONFLICT makes a repeat, or two at
     * once, a no-op rather than a unique-constraint exception, which would
     * mark the surrounding transaction rollback-only (as in
     * UserRepository.insertIfAbsent). Returns the rows inserted: 1 for a new
     * follow, 0 if it already existed, so only a new follow notifies.
     */
    @Modifying
    @Query(value = "INSERT INTO follows (follower_id, followee_id) VALUES (:followerId, :followeeId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int followIfAbsent(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
