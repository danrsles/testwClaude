package com.example.demo.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/**
 * The signup rules that need no database. Capacity under concurrency, and
 * everything else that depends on SQL, is in WantSignupsIT.
 */
@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock
    private WantRepository wantRepository;

    @Mock
    private WantSignupRepository wantSignupRepository;

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private SignupService signupService;

    @BeforeEach
    void setUp() {
        signupService = new SignupService(wantRepository, wantSignupRepository, userService, notificationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    private final User dani = withId(new User("dani", "user_dani"), 1L);

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void theOwnerCannotWithdraw() {
        Want pizza = new Want("Pizza Friday", Category.FOOD, dani, 4);
        ReflectionTestUtils.setField(pizza, "id", 7L);
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findByIdForUpdate(7L)).willReturn(Optional.of(pizza));

        // Giving up the owner's seat would be cancelling the want.
        assertThatThrownBy(() -> signupService.withdraw(7L, DANI))
                .isInstanceOf(OwnerCannotWithdrawException.class);
        then(wantSignupRepository).should(never()).withdraw(anyLong(), anyLong());
        then(notificationService).should(never()).notify(any(), any(), any(), any());
    }

    @Test
    void anOpenWantIsNeverFull() {
        User alex = withId(new User("alex", "user_alex"), 2L);
        Want games = new Want("Board games", Category.GAME, alex, null);
        ReflectionTestUtils.setField(games, "id", 7L);
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findByIdForUpdate(7L)).willReturn(Optional.of(games));
        given(wantRepository.isInFeed(7L, 1L)).willReturn(true);
        // Lenient: a fix may skip counting altogether when there is no limit.
        lenient().when(wantSignupRepository.countByWantId(7L)).thenReturn(1_000L);
        given(wantSignupRepository.signUpIfAbsent(7L, 1L)).willReturn(1);

        signupService.signUp(7L, DANI);

        then(wantSignupRepository).should().signUpIfAbsent(7L, 1L);
    }

    @Test
    void signingUpToAnUnknownWantIsNotFound() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> signupService.signUp(99L, DANI))
                .isInstanceOf(WantNotFoundException.class);
        then(wantSignupRepository).should(never()).signUpIfAbsent(anyLong(), anyLong());
    }

    @Test
    void noSignupAfterTheDeadlineEvenBeforeTheJobRuns() {
        // closed_at is still null: the job has not run, but the deadline alone closes it.
        User alex = withId(new User("alex", "user_alex"), 2L);
        Want pizza = new Want("Pizza Friday", Category.FOOD, alex, 4);
        ReflectionTestUtils.setField(pizza, "id", 7L);
        pizza.setExpiresAt(NOW.minusSeconds(1));
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findByIdForUpdate(7L)).willReturn(Optional.of(pizza));
        given(wantRepository.isInFeed(7L, 1L)).willReturn(true);

        assertThatThrownBy(() -> signupService.signUp(7L, DANI)).isInstanceOf(WantClosedException.class);
        then(wantSignupRepository).should(never()).signUpIfAbsent(anyLong(), anyLong());
    }
}
