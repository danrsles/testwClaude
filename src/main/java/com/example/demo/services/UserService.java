package com.example.demo.services;

import java.util.Objects;

import com.example.demo.models.User;
import com.example.demo.repositories.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The local user for a signed-in Clerk account, created on its first
     * request. Clerk owns sign-up, so there is no endpoint that creates users;
     * a valid token is enough to have one.
     *
     * Clerk also owns the username, the email and the last sign-in time: what
     * the token carries is copied here on creation and whenever it changes in
     * Clerk. The user is managed, so a change is written when the transaction
     * commits, and an unchanged user, as on almost every request, is not
     * written at all. Without a username, the username is the Clerk id, which
     * is always unique.
     */
    @Transactional
    public User current(ClerkIdentity who) {
        User user = userRepository.findByClerkId(who.clerkId()).orElseGet(() -> create(who));
        syncUsername(user, who.username());
        syncAccountDetails(user, who);
        return user;
    }

    private User create(ClerkIdentity who) {
        userRepository.insertIfAbsent(who.clerkId(), who.clerkId());
        return userRepository.findByClerkId(who.clerkId()).orElseThrow(() -> new IllegalStateException(
                "Could not create a user for " + who.clerkId()));
    }

    /**
     * Clerk keeps usernames unique among its own users, but this table also
     * holds users from before logins (the seeded dani) and names not yet
     * updated after a rename in Clerk. A name taken here is skipped rather
     * than failing the request; the user keeps their current one until it
     * frees up.
     */
    private void syncUsername(User user, String wanted) {
        if (wanted == null || wanted.isBlank() || wanted.equals(user.getUsername())) {
            return;
        }
        if (userRepository.existsByUsernameAndIdNot(wanted, user.getId())) {
            return;
        }
        user.setUsername(wanted);
    }

    /** A missing claim never erases what is stored. */
    private static void syncAccountDetails(User user, ClerkIdentity who) {
        String email = who.email() == null || who.email().isBlank() ? null : who.email().strip();
        if (email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
        }
        if (who.lastSignInAt() != null && !Objects.equals(who.lastSignInAt(), user.getLastSignInAt())) {
            user.setLastSignInAt(who.lastSignInAt());
        }
    }
}
