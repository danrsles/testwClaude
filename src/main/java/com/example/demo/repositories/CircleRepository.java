package com.example.demo.repositories;

import java.util.List;
import java.util.Optional;

import com.example.demo.models.Circle;
import com.example.demo.models.User;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Circles, and their members, who are reached through native SQL because
 * circle_members has no entity of its own.
 */
public interface CircleRepository extends JpaRepository<Circle, Long> {

    /** A circle that hasn't been deleted, without locking it, for reads. */
    @Query("select c from Circle c where c.id = :id and c.archivedAt is null")
    Optional<Circle> findLive(@Param("id") Long id);

    /**
     * As findLive, with SELECT ... FOR UPDATE, held until the transaction
     * ends. Every change to a circle or its members takes it, so they take
     * turns. One that waited re-checks archived_at once the other commits,
     * so a circle deleted meanwhile comes back empty.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Circle c where c.id = :id and c.archivedAt is null")
    Optional<Circle> findLiveForUpdate(@Param("id") Long id);

    /** Circles a user owns or is in, not deleted, oldest first. */
    @Query(value = "SELECT c.* FROM circles c WHERE c.archived_at IS NULL "
            + "AND (c.owner_id = :userId OR EXISTS "
            + "(SELECT 1 FROM circle_members m WHERE m.circle_id = c.id AND m.member_id = :userId)) "
            + "ORDER BY c.id", nativeQuery = true)
    List<Circle> findMine(@Param("userId") Long userId);

    /** For duplicate names on create. Deleted circles don't count, so their names can be used again. */
    boolean existsByOwnerIdAndNameIgnoreCaseAndArchivedAtIsNull(Long ownerId, String name);

    /** As above, on rename: the circle being renamed doesn't clash with itself. */
    boolean existsByOwnerIdAndNameIgnoreCaseAndArchivedAtIsNullAndIdNot(Long ownerId, String name, Long id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM circle_members WHERE circle_id = :circleId AND member_id = :userId)",
            nativeQuery = true)
    boolean isMember(@Param("circleId") Long circleId, @Param("userId") Long userId);

    /** A circle's members, not its owner, in the order they were added. */
    @Query(value = "SELECT u.* FROM users u JOIN circle_members m ON m.member_id = u.id "
            + "WHERE m.circle_id = :circleId ORDER BY m.created_at, u.id", nativeQuery = true)
    List<User> findMembers(@Param("circleId") Long circleId);

    /**
     * Adds a member unless they are in already. Returns the rows inserted, so
     * only a new member is told. The caller has locked the follow row first,
     * so the key to follows can't fail here.
     */
    @Modifying
    @Query(value = "INSERT INTO circle_members (circle_id, owner_id, member_id) VALUES (:circleId, :ownerId, :memberId) "
            + "ON CONFLICT (circle_id, member_id) DO NOTHING", nativeQuery = true)
    int addMemberIfAbsent(@Param("circleId") Long circleId, @Param("ownerId") Long ownerId,
            @Param("memberId") Long memberId);

    @Modifying
    @Query(value = "DELETE FROM circle_members WHERE circle_id = :circleId AND member_id = :memberId",
            nativeQuery = true)
    int removeMember(@Param("circleId") Long circleId, @Param("memberId") Long memberId);
}
