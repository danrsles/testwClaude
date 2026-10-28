package com.example.demo.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.models.WantStatus;
import com.example.demo.repositories.WantRepository;
import com.example.demo.repositories.WantSignupRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/** The ownership and capacity rules, which the controller tests mock away. */
@ExtendWith(MockitoExtension.class)
class WantServiceTest {

    @Mock
    private WantRepository wantRepository;

    @Mock
    private WantSignupRepository wantSignupRepository;

    @Mock
    private UserService userService;

    @Mock
    private NotificationService notificationService;

    /** "Now" for every test here, so a deadline can sit a second either side of it. */
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private WantService wantService;

    @BeforeEach
    void setUp() {
        wantService = new WantService(wantRepository, wantSignupRepository, userService, notificationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");
    private static final ClerkIdentity MALLORY = new ClerkIdentity("user_mallory", "mallory");

    private final User dani = withId(new User("dani", "user_dani"), 1L);
    // The seeded user from before logins: no Clerk account can match it.
    private final User legacy = new User("legacy");

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void createsWantsForTheSignedInUser() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.save(any(Want.class))).willAnswer(call -> call.getArgument(0));

        WantResponse saved = wantService.create("Tacos", Category.FOOD, 1, null, DANI);

        assertThat(saved.userId()).isEqualTo(1L);
    }

    @Test
    void ownerMayUpdateTheirWant() {
        Want want = new Want("Ramen", Category.FOOD, dani);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);

        WantResponse updated = wantService.update(1L, "Pho", Category.FOOD, null, null, null, null, DANI);

        assertThat(updated.message()).isEqualTo("Pho");
        assertThat(updated.userId()).isEqualTo(1L);
    }

    @Test
    void othersMayNotUpdateIt() {
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(new Want("Ramen", Category.FOOD, dani)));

        assertThatThrownBy(() -> wantService.update(1L, "Mine now", Category.FOOD, null, null, null, null, MALLORY))
                .isInstanceOf(WantForbiddenException.class);
        then(wantRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void capacityCannotBeLowered() {
        // Lowering it could take a seat from someone who already has one.
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(new Want("Pizza", Category.FOOD, dani, 3)));

        assertThatThrownBy(() -> wantService.update(1L, "Pizza", Category.FOOD, 2, null, null, null, DANI))
                .isInstanceOf(CapacityLoweredException.class);
        then(wantRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void openingAWantRemovesItsLimit() {
        Want want = new Want("Pizza", Category.FOOD, dani, 2);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);

        WantResponse opened = wantService.update(1L, "Pizza", Category.FOOD, null, true, null, null, DANI);

        assertThat(want.getCapacity()).isNull();
        assertThat(opened.capacity()).isNull();
    }

    @Test
    void openingAWantTellsTheLineup() {
        // So they know they can bring others.
        Want want = new Want("Pizza", Category.FOOD, dani, 2);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);

        wantService.update(1L, "Pizza", Category.FOOD, null, true, null, null, DANI);

        then(notificationService).should().notifyLineup(1L, dani, NotificationType.WANT_OPEN_INVITE, null);
    }

    @Test
    void anOpenWantCannotBeGivenALimit() {
        // Any limit is lower than none, and could leave more people in than seats.
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(new Want("Pizza", Category.FOOD, dani, null)));

        assertThatThrownBy(() -> wantService.update(1L, "Pizza", Category.FOOD, 5, null, null, null, DANI))
                .isInstanceOf(CapacityLoweredException.class);
        then(wantRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void limitingAnOpenWantCapsItAtItsLineup() {
        // Exactly the people already in, so nobody loses a seat and nobody new gets one.
        Want want = new Want("Pizza", Category.FOOD, dani, null);
        ReflectionTestUtils.setField(want, "id", 1L);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);
        given(wantSignupRepository.countByWantId(1L)).willReturn(4L);

        WantResponse limited = wantService.update(1L, "Pizza", Category.FOOD, null, false, null, null, DANI);

        assertThat(want.getCapacity()).isEqualTo(4);
        assertThat(limited.capacity()).isEqualTo(4);
    }

    @Test
    void limitingAnOpenWantTellsTheLineup() {
        Want want = new Want("Pizza", Category.FOOD, dani, null);
        ReflectionTestUtils.setField(want, "id", 1L);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);
        given(wantSignupRepository.countByWantId(1L)).willReturn(4L);

        wantService.update(1L, "Pizza", Category.FOOD, null, false, null, null, DANI);

        then(notificationService).should().notifyLineup(1L, dani, NotificationType.WANT_LIMITED, null);
    }

    @Test
    void limitingALimitedWantChangesNothing() {
        Want want = new Want("Pizza", Category.FOOD, dani, 3);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);

        wantService.update(1L, "Pizza", Category.FOOD, null, false, null, null, DANI);

        assertThat(want.getCapacity()).isEqualTo(3);
        then(notificationService).should(never()).notifyLineup(any(), any(), any(), any());
    }

    @Test
    void onlyTheOwnerMayCloseReopenStartEndOrCancel() {
        Want want = new Want("Ramen", Category.FOOD, dani);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));

        assertThatThrownBy(() -> wantService.close(1L, MALLORY)).isInstanceOf(WantForbiddenException.class);
        assertThatThrownBy(() -> wantService.reopen(1L, null, MALLORY)).isInstanceOf(WantForbiddenException.class);
        assertThatThrownBy(() -> wantService.start(1L, MALLORY)).isInstanceOf(WantForbiddenException.class);
        assertThatThrownBy(() -> wantService.end(1L, MALLORY)).isInstanceOf(WantForbiddenException.class);
        assertThatThrownBy(() -> wantService.cancel(1L, MALLORY)).isInstanceOf(WantForbiddenException.class);

        assertThat(want.getStatus(NOW)).isEqualTo(WantStatus.ACTIVE);
        assertThat(want.isHappening()).isFalse();
        then(notificationService).shouldHaveNoInteractions();
        then(wantRepository).should(never()).delete(any());
    }

    @Test
    void aNewWantIsNotHappening() {
        Want want = new Want("Ramen", Category.FOOD, dani);

        assertThat(want.isHappening()).isFalse();
        assertThat(want.getStatus(NOW)).isEqualTo(WantStatus.ACTIVE);
    }

    @Test
    void anEndedWantIsEndedEvenIfItWasClosed() {
        // Status checks cancelled, then ended, then closed, then active.
        Want want = new Want("Ramen", Category.FOOD, dani);
        want.start(Instant.parse("2026-10-23T19:00:00Z"));
        want.close(Instant.parse("2026-10-23T19:30:00Z"));
        want.end(Instant.parse("2026-10-23T22:00:00Z"));

        assertThat(want.getStatus(NOW)).isEqualTo(WantStatus.ENDED);
        assertThat(want.isHappening()).isFalse();
    }

    @Test
    void wantsWithoutAClerkOwnerAreReadOnly() {
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(new Want("Ramen", Category.FOOD, legacy)));

        assertThatThrownBy(() -> wantService.cancel(1L, DANI))
                .isInstanceOf(WantForbiddenException.class);
    }

    @Test
    void unknownWantIsNotFound() {
        given(wantRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> wantService.cancel(99L, DANI))
                .isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void listsOnlyTheFeedOfTheSignedInUser() {
        // Their own wants plus those of the people they follow, never everyone's.
        List<Want> feed = List.of(new Want("Ramen", Category.FOOD, dani));
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findFeed(1L)).willReturn(feed);

        assertThat(wantService.findAll(DANI)).extracting(WantResponse::message).containsExactly("Ramen");
        then(wantRepository).should(never()).findAll();
    }

    @Test
    void bringsTheSignedInUserUpToDateBeforeListing() {
        // So a picture just changed in Clerk is in this response, not only the next one.
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findFeed(1L)).willReturn(List.of());

        wantService.findAll(DANI);

        InOrder order = inOrder(userService, wantRepository);
        order.verify(userService).current(DANI);
        order.verify(wantRepository).findFeed(1L);
    }

    @Test
    void bringsTheSignedInUserUpToDateBeforeFiltering() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.findFeedByCategory(1L, Category.FOOD)).willReturn(List.of());

        wantService.findByCategory(Category.FOOD, DANI);

        InOrder order = inOrder(userService, wantRepository);
        order.verify(userService).current(DANI);
        order.verify(wantRepository).findFeedByCategory(1L, Category.FOOD);
    }

    /** dani's Pizza Friday, found as want 1 under the row lock. */
    private Want editable(Instant expiresAt) {
        Want want = new Want("Pizza Friday", Category.FOOD, dani, 4);
        want.setExpiresAt(expiresAt);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        return want;
    }

    private void savesItself(Want want) {
        given(wantRepository.saveAndFlush(want)).willReturn(want);
    }

    @Test
    void editingSetsOrMovesTheDeadline() {
        Want want = editable(null);
        savesItself(want);

        WantResponse set = wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, NOW.plusSeconds(3600),
                null, DANI);
        assertThat(want.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(set.expiresAt()).isEqualTo(NOW.plusSeconds(3600));

        wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, NOW.plusSeconds(7200), null, DANI);
        assertThat(want.getExpiresAt()).isEqualTo(NOW.plusSeconds(7200));
    }

    @Test
    void anEditThatLeavesOutTheDeadlineKeepsIt() {
        // As with capacity, leaving it out means "keep", so older clients cannot erase it.
        Want want = editable(NOW.plusSeconds(3600));
        savesItself(want);

        wantService.update(1L, "Pizza Saturday", Category.FOOD,
                null,   // capacity
                null,   // openInvite
                null,   // expiresAt: not sent, the point of this test
                null,   // noDeadline
                DANI);

        assertThat(want.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void editingCanRemoveTheDeadline() {
        Want want = editable(NOW.plusSeconds(3600));
        savesItself(want);

        WantResponse removed = wantService.update(1L, "Pizza Friday", Category.FOOD,
                null,   // capacity
                null,   // openInvite
                null,   // expiresAt
                true,   // noDeadline: the point of this test
                DANI);

        assertThat(want.getExpiresAt()).isNull();
        assertThat(removed.expiresAt()).isNull();
    }

    @Test
    void aWantPastItsDeadlineCannotBeEdited() {
        // Past its deadline it is closed, whether or not the job has written closed_at yet.
        editable(NOW.minusSeconds(1));

        assertThatThrownBy(() -> wantService.update(1L, "Pizza Saturday", Category.FOOD, null, null,
                NOW.plusSeconds(3600), null, DANI)).isInstanceOf(WantClosedException.class);
        then(wantRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void changingTheDeadlineTellsTheLineup() {
        Want want = editable(NOW.plusSeconds(3600));
        savesItself(want);

        wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, NOW.plusSeconds(7200), null, DANI);

        then(notificationService).should().notifyLineup(1L, dani, NotificationType.WANT_DEADLINE_CHANGED, null);
        // The only lineup notification; followers may still be told about it (WANT_POSTED).
        then(notificationService).should(times(1)).notifyLineup(any(), any(), any(), any());
    }

    @Test
    void settingAFirstDeadlineTellsTheLineup() {
        // From none to one is a change too, though the old value is null.
        Want want = editable(null);
        savesItself(want);

        wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, NOW.plusSeconds(3600), null, DANI);

        then(notificationService).should().notifyLineup(1L, dani, NotificationType.WANT_DEADLINE_CHANGED, null);
        // The only lineup notification; followers may still be told about it (WANT_POSTED).
        then(notificationService).should(times(1)).notifyLineup(any(), any(), any(), any());
    }

    @Test
    void removingTheDeadlineTellsTheLineup() {
        Want want = editable(NOW.plusSeconds(3600));
        savesItself(want);

        wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, null, true, DANI);

        then(notificationService).should().notifyLineup(1L, dani, NotificationType.WANT_DEADLINE_CHANGED, null);
        // The only lineup notification; followers may still be told about it (WANT_POSTED).
        then(notificationService).should(times(1)).notifyLineup(any(), any(), any(), any());
    }

    @Test
    void sendingTheSameDeadlineTellsNobody() {
        Want want = editable(NOW.plusSeconds(3600));
        savesItself(want);

        wantService.update(1L, "Pizza Friday", Category.FOOD, null, null, NOW.plusSeconds(3600), null, DANI);

        then(notificationService).should(never()).notifyLineup(any(), any(), any(), any());
    }

    @Test
    void reopeningClearsADeadlineThatHasPassed() {
        // Closed by hand, then the deadline passed while it was closed: kept, it would close the want again at once.
        Want expired = editable(NOW.minusSeconds(60));
        expired.close(NOW.minusSeconds(120));

        WantResponse reopened = wantService.reopen(1L, null, DANI);

        assertThat(expired.getExpiresAt()).isNull();
        assertThat(reopened.status()).isEqualTo(WantStatus.ACTIVE);
    }

    @Test
    void reopeningKeepsADeadlineStillAhead() {
        // Closed by hand for a while: reopening without a new deadline keeps the one it had.
        Want closedEarly = editable(NOW.plusSeconds(3600));
        closedEarly.close(NOW.minusSeconds(60));

        WantResponse reopened = wantService.reopen(1L, null, DANI);

        assertThat(closedEarly.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(reopened.status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(reopened.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void reopeningWithANewDeadlineSetsIt() {
        Want want = editable(NOW.minusSeconds(60));

        WantResponse reopened = wantService.reopen(1L, NOW.plusSeconds(86_400), DANI);

        assertThat(want.getExpiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(reopened.status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(reopened.expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
    }

    /** As save does, giving the new want its id. */
    private static Want savedAs(Want want, Long id) {
        ReflectionTestUtils.setField(want, "id", id);
        return want;
    }

    @Test
    void createAnnouncesAWantWithAFreeSeat() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.save(any(Want.class))).willAnswer(call -> savedAs(call.getArgument(0), 7L));

        wantService.create("Pizza", Category.FOOD, 4, null, DANI);

        then(notificationService).should().announce(7L, dani);
    }

    @Test
    void createAnnouncesAnOpenInvite() {
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.save(any(Want.class))).willAnswer(call -> savedAs(call.getArgument(0), 7L));

        wantService.create("Pizza", Category.FOOD, null, null, DANI);

        then(notificationService).should().announce(7L, dani);
    }

    @Test
    void createDoesNotAnnounceAWantForOneSeat() {
        // The owner already holds it, so there is nothing to join.
        given(userService.current(DANI)).willReturn(dani);
        given(wantRepository.save(any(Want.class))).willAnswer(call -> savedAs(call.getArgument(0), 7L));

        wantService.create("Pizza", Category.FOOD, 1, null, DANI);

        then(notificationService).should(never()).announce(any(), any());
    }

    @Test
    void updateAnnouncesAWantThatBecameJoinable() {
        Want want = new Want("Pizza", Category.FOOD, dani, 1);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);
        given(wantSignupRepository.countByWantId(1L)).willReturn(1L);

        wantService.update(1L, "Pizza", Category.FOOD, 3, null, null, null, DANI);

        then(notificationService).should().announce(1L, dani);
    }

    @Test
    void updateDoesNotAnnounceAFullWant() {
        Want want = new Want("Pizza", Category.FOOD, dani, 2);
        given(wantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(want));
        given(wantRepository.saveAndFlush(want)).willReturn(want);
        given(wantSignupRepository.countByWantId(1L)).willReturn(2L);

        wantService.update(1L, "Pizza night", Category.FOOD, null, null, null, null, DANI);

        then(notificationService).should(never()).announce(any(), any());
    }
}
