package com.example.demo.repositories;

import java.util.Collection;
import java.util.List;

import com.example.demo.dto.UserSummary;
import com.example.demo.models.WantSignup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Seats on wants. Nothing here checks capacity: SignupService does that while
 * holding the want's row lock (WantRepository.findByIdForUpdate), so the
 * count it reads cannot change before its insert.
 */
public interface WantSignupRepository extends JpaRepository<WantSignup, Long> {

    boolean existsByWantIdAndUserId(Long wantId, Long userId);

    long countByWantId(Long wantId);

    /**
     * The lineups of several wants, as [wantId, userId, username, imageUrl]
     * rows in signup order: one query for a whole feed, never one per want.
     */
    @Query("select s.want.id, s.user.id, s.user.username, s.user.imageUrl "
            + "from WantSignup s where s.want.id in :wantIds order by s.id")
    List<Object[]> findLineups(@Param("wantIds") Collection<Long> wantIds);

    /** The lineup by public fields only, in the order people signed up; the owner is first. */
    @Query("select new com.example.demo.dto.UserSummary(s.user.id, s.user.username, s.user.imageUrl) "
            + "from WantSignup s where s.want.id = :wantId order by s.id")
    List<UserSummary> findLineup(@Param("wantId") Long wantId);

    /**
     * Takes a seat unless already holding one. ON CONFLICT makes a repeat a
     * no-op rather than a unique-constraint exception, which would mark the
     * surrounding transaction rollback-only (as in FollowRepository.followIfAbsent).
     * Returns the rows inserted, so only a new signup notifies.
     */
    @Modifying
    @Query(value = "INSERT INTO want_signups (want_id, user_id) VALUES (:wantId, :userId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int signUpIfAbsent(@Param("wantId") Long wantId, @Param("userId") Long userId);

    /** Gives up a seat. Returns the rows deleted, so only a real withdrawal notifies. */
    @Modifying
    @Query(value = "DELETE FROM want_signups WHERE want_id = :wantId AND user_id = :userId", nativeQuery = true)
    int withdraw(@Param("wantId") Long wantId, @Param("userId") Long userId);
}
