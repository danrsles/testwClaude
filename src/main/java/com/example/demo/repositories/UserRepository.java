package com.example.demo.repositories;

import java.util.Optional;

import com.example.demo.models.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByClerkId(String clerkId);

    boolean existsByUsernameAndIdNot(String username, Long id);

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
