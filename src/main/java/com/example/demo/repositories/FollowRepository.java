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

    void deleteByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    /** The ids of everyone a user follows, for marking search results. */
    @Query("select f.followee.id from Follow f where f.follower.id = :followerId")
    Set<Long> findFolloweeIds(@Param("followerId") Long followerId);

    /**
     * Follows unless already following. ON CONFLICT makes a repeat, or two at
     * once, a no-op rather than a unique-constraint exception, which would
     * mark the surrounding transaction rollback-only (as in
     * UserRepository.insertIfAbsent).
     */
    @Modifying
    @Query(value = "INSERT INTO follows (follower_id, followee_id) VALUES (:followerId, :followeeId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    void followIfAbsent(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
