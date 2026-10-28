package com.example.demo.repositories;

import java.util.List;
import java.util.Optional;

import com.example.demo.models.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByClerkId(String clerkId);

    /**
     * Locks a user's row until the transaction ends, so that user's circle
     * creates and renames take turns and a duplicate name is caught by the
     * check, not by the unique index. FOR NO KEY UPDATE rather than FOR
     * UPDATE, so inserts that only reference the user, such as a
     * notification to them, don't wait for it.
     */
    @Query(value = "SELECT id FROM users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Long lockForCircleNames(@Param("id") Long id);

    boolean existsByUsernameAndIdNot(String username, Long id);

    /**
     * People search: names containing the text, ignoring case, never the
     * searcher. Spring Data escapes % and _ in Containing, so they match
     * literally.
     */
    List<User> findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername(String text, Long id);

    /**
     * Creates the user for a Clerk account unless it exists. ON CONFLICT makes
     * two simultaneous first requests safe: one inserts, the other does
     * nothing, and neither fails its transaction the way a caught
     * unique-constraint exception would. UserService passes the Clerk id as
     * the username, which cannot clash, and renames afterwards.
     */
    @Modifying
    @Query(value = "INSERT INTO users (username, clerk_id) VALUES (:username, :clerkId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(@Param("clerkId") String clerkId, @Param("username") String username);
}
