package com.example.demo.services;

import java.util.List;
import java.util.Set;

import com.example.demo.dto.PersonResult;

import com.example.demo.models.Follow;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.FollowRepository;
import com.example.demo.repositories.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** Who may follow whom. The follower is always the signed-in user. */
@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private FollowService followService;

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    private final User dani = withId(new User("dani", "user_dani"), 1L);
    private final User alex = withId(new User("alex"), 2L);

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @BeforeEach
    void signInAsDani() {
        given(userService.current(DANI)).willReturn(dani);
    }

    @Test
    void followsAnotherUserAndTellsThem() {
        given(userRepository.existsById(2L)).willReturn(true);
        given(followRepository.followIfAbsent(1L, 2L)).willReturn(1);

        followService.follow(2L, DANI);

        then(followRepository).should().followIfAbsent(1L, 2L);
        then(notificationService).should().notify(2L, dani, NotificationType.FOLLOW);
    }

    @Test
    void followingAgainDoesNotTellThemTwice() {
        given(userRepository.existsById(2L)).willReturn(true);
        given(followRepository.followIfAbsent(1L, 2L)).willReturn(0);

        followService.follow(2L, DANI);

        then(notificationService).should(never()).notify(any(), any(), any());
    }

    @Test
    void refusesToFollowYourself() {
        assertThatThrownBy(() -> followService.follow(1L, DANI))
                .isInstanceOf(CannotFollowYourselfException.class);
        then(followRepository).should(never()).followIfAbsent(anyLong(), anyLong());
        then(notificationService).should(never()).notify(any(), any(), any());
    }

    @Test
    void unknownUserIsNotFound() {
        given(userRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> followService.follow(99L, DANI))
                .isInstanceOf(UserNotFoundException.class);
        then(followRepository).should(never()).followIfAbsent(anyLong(), anyLong());
    }

    @Test
    void unfollowRemovesTheFollow() {
        followService.unfollow(2L, DANI);

        then(followRepository).should().deleteByFollowerIdAndFolloweeId(1L, 2L);
    }

    @Test
    void listsTheUsersYouFollow() {
        given(followRepository.findByFollowerIdOrderById(1L)).willReturn(List.of(new Follow(dani, alex)));

        assertThat(followService.following(DANI)).containsExactly(alex);
    }

    @Test
    void searchMarksWhomYouFollowAndLeavesYouOut() {
        User sal = withId(new User("sal"), 5L);
        given(followRepository.findFolloweeIds(1L)).willReturn(Set.of(2L));
        given(userRepository.findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername("al", 1L))
                .willReturn(List.of(alex, sal));

        assertThat(followService.search("  al ", DANI)).containsExactly(
                new PersonResult(2L, "alex", null, true),
                new PersonResult(5L, "sal", null, false));
    }

    @Test
    void aBlankSearchFindsNoOne() {
        assertThat(followService.search("   ", DANI)).isEmpty();
        then(userRepository).should(never())
                .findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername(any(), anyLong());
    }
}
