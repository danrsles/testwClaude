package com.example.demo.repositories;

import java.util.Optional;

import com.example.demo.models.UserProfile;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
}
