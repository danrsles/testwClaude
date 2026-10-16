package com.example.demo.services;

import com.example.demo.models.User;
import com.example.demo.models.UserProfile;
import com.example.demo.repositories.UserProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own profile, and nobody else's. Profiles hold email
 * addresses, so there is deliberately no way to list or read another user's.
 */
@Service
public class UserProfileService {

    private final UserProfileRepository profileRepository;
    private final UserService userService;

    public UserProfileService(UserProfileRepository profileRepository, UserService userService) {
        this.profileRepository = profileRepository;
        this.userService = userService;
    }

    @Transactional
    public UserProfile findMine(ClerkIdentity who) {
        return requireMine(userService.current(who));
    }

    @Transactional
    public UserProfile create(ClerkIdentity who, String email, String nickname, String s3Url) {
        User user = userService.current(who);
        if (profileRepository.existsByUserId(user.getId())) {
            throw new UserProfileConflictException("You already have a profile");
        }
        if (profileRepository.existsByEmail(email)) {
            throw new UserProfileConflictException("Email " + email + " is already in use");
        }
        return profileRepository.save(new UserProfile(user, email, nickname, blankToNull(s3Url)));
    }

    /** Full replacement of the editable fields. The owning user never changes. */
    @Transactional
    public UserProfile update(ClerkIdentity who, String email, String nickname, String s3Url) {
        UserProfile profile = requireMine(userService.current(who));
        if (profileRepository.existsByEmailAndIdNot(email, profile.getId())) {
            throw new UserProfileConflictException("Email " + email + " is already in use");
        }

        profile.setEmail(email);
        profile.setNickname(nickname);
        profile.setS3Url(blankToNull(s3Url));

        return profileRepository.save(profile);
    }

    @Transactional
    public void delete(ClerkIdentity who) {
        profileRepository.delete(requireMine(userService.current(who)));
    }

    private UserProfile requireMine(User user) {
        return profileRepository.findByUserId(user.getId())
                .orElseThrow(UserProfileNotFoundException::new);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
