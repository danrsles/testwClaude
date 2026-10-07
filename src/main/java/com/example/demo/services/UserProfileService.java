package com.example.demo.services;

import java.util.List;

import com.example.demo.models.User;
import com.example.demo.models.UserProfile;
import com.example.demo.repositories.UserProfileRepository;
import com.example.demo.repositories.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private final UserProfileRepository profileRepository;
    private final UserRepository userRepository;

    public UserProfileService(UserProfileRepository profileRepository, UserRepository userRepository) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
    }

    public List<UserProfile> findAll() {
        return profileRepository.findAll();
    }

    /** A list rather than an Optional, so the list endpoint keeps one shape whether filtered or not. */
    public List<UserProfile> findByUserId(Long userId) {
        return profileRepository.findByUserId(userId).stream().toList();
    }

    public UserProfile findById(Long profileId) {
        return profileRepository.findById(profileId)
                .orElseThrow(() -> new UserProfileNotFoundException(profileId));
    }

    @Transactional
    public UserProfile create(Long userId, String email, String nickname, String s3Url) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (profileRepository.existsByUserId(userId)) {
            throw new UserProfileConflictException("User " + userId + " already has a profile");
        }
        if (profileRepository.existsByEmail(email)) {
            throw new UserProfileConflictException("Email " + email + " is already in use");
        }
        return profileRepository.save(new UserProfile(user, email, nickname, blankToNull(s3Url)));
    }

    /** Full replacement of the editable fields. The owning user never changes. */
    @Transactional
    public UserProfile update(Long profileId, String email, String nickname, String s3Url) {
        UserProfile profile = findById(profileId);
        if (profileRepository.existsByEmailAndIdNot(email, profileId)) {
            throw new UserProfileConflictException("Email " + email + " is already in use");
        }

        profile.setEmail(email);
        profile.setNickname(nickname);
        profile.setS3Url(blankToNull(s3Url));

        return profileRepository.save(profile);
    }

    @Transactional
    public void delete(Long profileId) {
        if (!profileRepository.existsById(profileId)) {
            throw new UserProfileNotFoundException(profileId);
        }
        profileRepository.deleteById(profileId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
