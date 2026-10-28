package com.example.demo.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.example.demo.models.Circle;
import com.example.demo.models.User;
import com.example.demo.repositories.CircleRepository;
import com.example.demo.repositories.FollowRepository;
import com.example.demo.repositories.UserRepository;

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
import static org.mockito.Mockito.never;

/** Who may do what to a circle, which the controller tests mock away. */
@ExtendWith(MockitoExtension.class)
class CircleServiceTest {

    @Mock
    private CircleRepository circleRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private CircleService circleService;

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    private final User dani = withId(new User("dani", "user_dani"), 1L);
    private final User alex = withId(new User("alex", "user_alex"), 2L);

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** Circle 7, owned by alex, found live under the lock. */
    private Circle alexsCircle() {
        Circle circle = new Circle(alex, "Climbing");
        ReflectionTestUtils.setField(circle, "id", 7L);
        given(circleRepository.findLiveForUpdate(7L)).willReturn(Optional.of(circle));
        return circle;
    }

    @BeforeEach
    void setUp() {
        circleService = new CircleService(circleRepository, followRepository, userRepository, userService,
                notificationService, Clock.fixed(NOW, ZoneOffset.UTC));
        given(userService.current(DANI)).willReturn(dani);
    }

    @Test
    void generalIsAReservedNameInAnyCase() {
        // "General" is what the screen calls all your followers.
        assertThatThrownBy(() -> circleService.create(" general ", DANI))
                .isInstanceOf(ReservedCircleNameException.class);
        assertThatThrownBy(() -> circleService.create("GENERAL", DANI))
                .isInstanceOf(ReservedCircleNameException.class);

        then(circleRepository).should(never()).save(any());
    }

    @Test
    void aDuplicateNameIsRefusedIgnoringCase() {
        given(circleRepository.existsByOwnerIdAndNameIgnoreCaseAndArchivedAtIsNull(1L, "climbing")).willReturn(true);

        assertThatThrownBy(() -> circleService.create("climbing", DANI))
                .isInstanceOf(DuplicateCircleNameException.class);
        then(circleRepository).should(never()).save(any());
    }

    @Test
    void aMemberGets403ForEveryOwnerAction() {
        alexsCircle();
        given(circleRepository.isMember(7L, 1L)).willReturn(true);

        assertThatThrownBy(() -> circleService.rename(7L, "Mine now", DANI))
                .isInstanceOf(CircleForbiddenException.class);
        assertThatThrownBy(() -> circleService.delete(7L, DANI)).isInstanceOf(CircleForbiddenException.class);
        assertThatThrownBy(() -> circleService.addMember(7L, 3L, DANI)).isInstanceOf(CircleForbiddenException.class);
    }

    @Test
    void anOutsiderGets404ForEveryCircleRoute() {
        Circle circle = alexsCircle();
        given(circleRepository.findLive(7L)).willReturn(Optional.of(circle));
        given(circleRepository.isMember(7L, 1L)).willReturn(false);

        assertThatThrownBy(() -> circleService.rename(7L, "Mine now", DANI))
                .isInstanceOf(CircleNotFoundException.class);
        assertThatThrownBy(() -> circleService.delete(7L, DANI)).isInstanceOf(CircleNotFoundException.class);
        assertThatThrownBy(() -> circleService.members(7L, DANI)).isInstanceOf(CircleNotFoundException.class);
        assertThatThrownBy(() -> circleService.addMember(7L, 3L, DANI)).isInstanceOf(CircleNotFoundException.class);
        assertThatThrownBy(() -> circleService.removeMember(7L, 3L, DANI))
                .isInstanceOf(CircleNotFoundException.class);
        assertThatThrownBy(() -> circleService.removeMember(7L, 1L, DANI))
                .isInstanceOf(CircleNotFoundException.class);
    }

    @Test
    void theFollowCheckRunsOnlyOnceYouAreTheOwner() {
        // Otherwise a 400 "doesn't follow you" would tell a member or an
        // outsider something about the owner's followers.
        alexsCircle();
        given(circleRepository.isMember(7L, 1L)).willReturn(true);

        assertThatThrownBy(() -> circleService.addMember(7L, 3L, DANI)).isInstanceOf(CircleForbiddenException.class);

        then(followRepository).should(never()).lockFollow(anyLong(), anyLong());
        then(circleRepository).should(never()).addMemberIfAbsent(anyLong(), anyLong(), anyLong());
    }

    @Test
    void theOwnerCannotLeave() {
        Circle circle = new Circle(dani, "Climbing");
        ReflectionTestUtils.setField(circle, "id", 7L);
        given(circleRepository.findLiveForUpdate(7L)).willReturn(Optional.of(circle));

        assertThatThrownBy(() -> circleService.removeMember(7L, 1L, DANI))
                .isInstanceOf(OwnerCannotLeaveException.class);
        then(circleRepository).should(never()).removeMember(anyLong(), anyLong());
    }

    @Test
    void aMemberCannotRemoveSomeoneElse() {
        alexsCircle();
        given(circleRepository.isMember(7L, 1L)).willReturn(true);

        assertThatThrownBy(() -> circleService.removeMember(7L, 3L, DANI))
                .isInstanceOf(CircleForbiddenException.class);
        then(circleRepository).should(never()).removeMember(anyLong(), anyLong());
    }
}
