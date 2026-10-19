package com.example.demo.services;

import java.time.Instant;
import java.util.Optional;

import com.example.demo.models.User;
import com.example.demo.repositories.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** Users are created on first sign-in and follow the username Clerk holds. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private static User stored(long id, String username, String clerkId) {
        User user = new User(username, clerkId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void createsANewUserUnderTheirClerkIdThenTakesTheirUsername() {
        User created = stored(3, "user_abc", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.empty(), Optional.of(created));
        given(userRepository.existsByUsernameAndIdNot("dani2", 3L)).willReturn(false);

        User user = userService.current(new ClerkIdentity("user_abc", "dani2"));

        // Inserted under the Clerk id, which cannot clash, then renamed.
        then(userRepository).should().insertIfAbsent("user_abc", "user_abc");
        assertThat(user.getUsername()).isEqualTo("dani2");
    }

    @Test
    void followsARenameInClerk() {
        User user = stored(3, "dani2", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));
        given(userRepository.existsByUsernameAndIdNot("dan", 3L)).willReturn(false);

        assertThat(userService.current(new ClerkIdentity("user_abc", "dan")).getUsername()).isEqualTo("dan");
        then(userRepository).should(never()).insertIfAbsent(anyString(), anyString());
    }

    @Test
    void keepsTheCurrentNameWhenTheClerkOneIsTakenHere() {
        // The seeded dani has no Clerk account, so Clerk lets someone else pick "dani".
        User user = stored(3, "user_abc", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));
        given(userRepository.existsByUsernameAndIdNot("dani", 3L)).willReturn(true);

        assertThat(userService.current(new ClerkIdentity("user_abc", "dani")).getUsername()).isEqualTo("user_abc");
    }

    @Test
    void keepsTheCurrentNameWhenTheTokenHasNone() {
        User user = stored(3, "dani2", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        assertThat(userService.current(new ClerkIdentity("user_abc", null)).getUsername()).isEqualTo("dani2");
        assertThat(userService.current(new ClerkIdentity("user_abc", " ")).getUsername()).isEqualTo("dani2");
        then(userRepository).should(never()).existsByUsernameAndIdNot(any(), any());
    }

    @Test
    void copiesTheEmailAndLastSignInFromTheToken() {
        User user = stored(3, "dani2", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));
        Instant signedIn = Instant.ofEpochSecond(1792177886);

        userService.current(new ClerkIdentity("user_abc", "dani2", " danrsles@gmail.com ", signedIn));

        assertThat(user.getEmail()).isEqualTo("danrsles@gmail.com");
        assertThat(user.getLastSignInAt()).isEqualTo(signedIn);
    }

    @Test
    void aMissingEmailOrSignInNeverErasesTheStoredOne() {
        User user = stored(3, "dani2", "user_abc");
        user.setEmail("danrsles@gmail.com");
        user.setLastSignInAt(Instant.ofEpochSecond(1792177886));
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        userService.current(new ClerkIdentity("user_abc", "dani2", null, null));
        userService.current(new ClerkIdentity("user_abc", "dani2", "", null));

        assertThat(user.getEmail()).isEqualTo("danrsles@gmail.com");
        assertThat(user.getLastSignInAt()).isEqualTo(Instant.ofEpochSecond(1792177886));
    }

    private static final String PICTURE = "https://img.clerk.com/dani.jpg";

    @Test
    void copiesThePictureFromTheToken() {
        User user = stored(3, "dani2", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        userService.current(new ClerkIdentity("user_abc", "dani2", null, null, PICTURE));

        assertThat(user.getImageUrl()).isEqualTo(PICTURE);
    }

    @Test
    void followsAChangeOfPicture() {
        // Removing your photo in Clerk arrives the same way: as the URL of its silhouette.
        User user = stored(3, "dani2", "user_abc");
        user.setImageUrl(PICTURE);
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        userService.current(new ClerkIdentity("user_abc", "dani2", null, null, "https://img.clerk.com/silhouette.png"));

        assertThat(user.getImageUrl()).isEqualTo("https://img.clerk.com/silhouette.png");
    }

    @Test
    void keepsThePictureWhenTheTokenHasNoPictureClaim() {
        // A session token template without imageUrl says nothing either way.
        User user = stored(3, "dani2", "user_abc");
        user.setImageUrl(PICTURE);
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        userService.current(new ClerkIdentity("user_abc", "dani2"));

        assertThat(user.getImageUrl()).isEqualTo(PICTURE);
    }

    @Test
    void doesNotQueryWhenTheNameIsUnchanged() {
        User user = stored(3, "dani2", "user_abc");
        given(userRepository.findByClerkId("user_abc")).willReturn(Optional.of(user));

        userService.current(new ClerkIdentity("user_abc", "dani2"));

        then(userRepository).should(never()).existsByUsernameAndIdNot(any(), any());
    }
}
