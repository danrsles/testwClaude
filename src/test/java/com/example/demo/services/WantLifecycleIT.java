package com.example.demo.services;

import java.util.List;
import java.util.UUID;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.WantStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closing, reopening and cancelling against a real PostgreSQL: what each
 * allows afterwards, who hears about it, and that doing one twice tells
 * nobody twice. Set up like WantSignupsIT: nothing is wrapped in a test
 * transaction, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantLifecycleIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private SignupService signupService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbc;

    /** A new user, created as on their first signed-in request. */
    private ClerkIdentity person(String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 8);
        ClerkIdentity who = new ClerkIdentity("user_" + unique, unique);
        userService.current(who);
        return who;
    }

    private Long idOf(ClerkIdentity who) {
        return userService.current(who).getId();
    }

    /** Someone who follows the owner, so the owner's wants are in their feed. */
    private ClerkIdentity follower(String name, ClerkIdentity owner) {
        ClerkIdentity who = person(name);
        followService.follow(idOf(owner), who);
        return who;
    }

    /** Someone who follows the owner and has taken a seat on the want. */
    private ClerkIdentity member(String name, ClerkIdentity owner, Long wantId) {
        ClerkIdentity who = follower(name, owner);
        signupService.signUp(wantId, who);
        return who;
    }

    private Long want(ClerkIdentity owner, String message, int capacity) {
        return wantService.create(message, Category.FOOD, capacity, owner).id();
    }

    private int seats(Long wantId) {
        return jdbc.queryForObject("select count(*) from want_signups where want_id = ?", Integer.class, wantId);
    }

    private List<Notification> ofType(ClerkIdentity recipient, NotificationType type) {
        return notificationService.mine(recipient).stream().filter(n -> n.getType() == type).toList();
    }

    @Test
    void noSignupWhenClosed() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(WantClosedException.class);

        assertThat(seats(pizza)).isEqualTo(1);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).isEmpty();
    }

    @Test
    void noWithdrawalWhenClosed() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> signupService.withdraw(pizza, alex)).isInstanceOf(WantClosedException.class);

        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_WITHDRAWN)).isEmpty();
    }

    /**
     * Closed means no signups, even from someone already in: the screen
     * disables Sign up, so a repeat only comes from a stale page or a script.
     */
    @Test
    void aMemberRepeatingSignupOnAClosedWantIsRefused() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(WantClosedException.class);

        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    /** Only an active want can be withdrawn from, so not holding a seat makes no difference. */
    @Test
    void withdrawingFromAClosedWantIsAlwaysRefused() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> signupService.withdraw(pizza, alex)).isInstanceOf(WantClosedException.class);

        assertThat(seats(pizza)).isEqualTo(1);
        assertThat(ofType(dani, NotificationType.WANT_WITHDRAWN)).isEmpty();
    }

    /** The 409 would tell a stranger the want exists, so they get the same 404 as for an open one. */
    @Test
    void aClosedWantIsNotFoundForSomeoneWhoCannotSeeIt() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, stranger)).isInstanceOf(WantNotFoundException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, stranger)).isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void noSignupOrWithdrawalWhenCancelled() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.cancel(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(WantCancelledException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, alex)).isInstanceOf(WantCancelledException.class);

        assertThat(seats(pizza)).isEqualTo(2);
    }

    /** A cancelled want leaves the feed, so to anyone not in its lineup it is as if it never existed. */
    @Test
    void aCancelledWantIsNotFoundForSomeoneOutsideItsLineup() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity sam = follower("sam", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.cancel(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantNotFoundException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, sam)).isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void closingNotifiesTheLineupButNotTheOwner() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = member("sam", dani, pizza);
        ClerkIdentity jordan = follower("jordan", dani);

        WantResponse closed = wantService.close(pizza, dani);

        assertThat(closed.status()).isEqualTo(WantStatus.CLOSED);
        assertThat(wantService.findAll(alex)).filteredOn(w -> w.id().equals(pizza))
                .extracting(WantResponse::status).containsExactly(WantStatus.CLOSED);
        for (ClerkIdentity inLineup : List.of(alex, sam)) {
            assertThat(ofType(inLineup, NotificationType.WANT_CLOSED)).singleElement().satisfies(n -> {
                assertThat(n.getActor().getId()).isEqualTo(idOf(dani));
                assertThat(n.getWantId()).isEqualTo(pizza);
                assertThat(n.getWantMessage()).isEqualTo("Pizza Friday");
            });
        }
        assertThat(ofType(dani, NotificationType.WANT_CLOSED)).isEmpty();
        assertThat(ofType(jordan, NotificationType.WANT_CLOSED)).isEmpty();
    }

    @Test
    void closingTwiceNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);

        wantService.close(pizza, dani);
        WantResponse again = wantService.close(pizza, dani);

        assertThat(again.status()).isEqualTo(WantStatus.CLOSED);
        assertThat(ofType(alex, NotificationType.WANT_CLOSED)).hasSize(1);
    }

    @Test
    void reopeningNotifiesTheLineupAndAllowsSignupsAgain() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = follower("sam", dani);
        wantService.close(pizza, dani);

        WantResponse reopened = wantService.reopen(pizza, dani);
        signupService.signUp(pizza, sam);

        assertThat(reopened.status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(seats(pizza)).isEqualTo(3);
        assertThat(ofType(alex, NotificationType.WANT_REOPENED)).singleElement()
                .satisfies(n -> assertThat(n.getWantId()).isEqualTo(pizza));
        assertThat(ofType(dani, NotificationType.WANT_REOPENED)).isEmpty();
    }

    @Test
    void reopeningAnOpenWantNotifiesNobody() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);

        WantResponse reopened = wantService.reopen(pizza, dani);

        assertThat(reopened.status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(ofType(alex, NotificationType.WANT_REOPENED)).isEmpty();
    }

    @Test
    void cancellingNotifiesTheLineupAndHidesTheWantFromTheFeed() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        Long tacos = want(dani, "Tacos", 2);
        ClerkIdentity alex = member("alex", dani, pizza);

        wantService.cancel(pizza, dani);

        assertThat(ofType(alex, NotificationType.WANT_CANCELLED)).singleElement().satisfies(n -> {
            assertThat(n.getWantId()).isEqualTo(pizza);
            assertThat(n.getWantMessage()).isEqualTo("Pizza Friday");
        });
        assertThat(ofType(dani, NotificationType.WANT_CANCELLED)).isEmpty();
        for (ClerkIdentity viewer : List.of(dani, alex)) {
            assertThat(wantService.findAll(viewer)).extracting(WantResponse::id).contains(tacos).doesNotContain(pizza);
            assertThat(wantService.findByCategory(Category.FOOD, viewer)).extracting(WantResponse::id)
                    .contains(tacos).doesNotContain(pizza);
        }
    }

    @Test
    void cancellingTwiceNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);

        wantService.cancel(pizza, dani);
        wantService.cancel(pizza, dani);

        assertThat(ofType(alex, NotificationType.WANT_CANCELLED)).hasSize(1);
    }

    /**
     * DELETE /wants/{id} calls cancel. The row stays, so the notifications
     * that point at it, and its lineup, survive.
     */
    @Test
    void deletingCancels() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        member("alex", dani, pizza);

        wantService.cancel(pizza, dani);

        assertThat(jdbc.queryForObject("select cancelled_at is not null from wants where id = ?", Boolean.class, pizza))
                .isTrue();
        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).singleElement()
                .satisfies(n -> assertThat(n.getWantId()).isEqualTo(pizza));
    }

    /** Cancelling is final. */
    @Test
    void aCancelledWantCannotBeClosedReopenedOrEdited() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.cancel(pizza, dani);

        assertThatThrownBy(() -> wantService.close(pizza, dani)).isInstanceOf(WantCancelledException.class);
        assertThatThrownBy(() -> wantService.reopen(pizza, dani)).isInstanceOf(WantCancelledException.class);
        assertThatThrownBy(() -> wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, dani))
                .isInstanceOf(WantCancelledException.class);
    }

    /** A closed want is fixed: to change it, the owner reopens it first. */
    @Test
    void aClosedWantMustBeReopenedToBeEdited() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(pizza, dani);

        assertThatThrownBy(() -> wantService.update(pizza, "Pizza Saturday", Category.FOOD, 6, dani))
                .isInstanceOf(WantClosedException.class);
        wantService.reopen(pizza, dani);
        WantResponse edited = wantService.update(pizza, "Pizza Saturday", Category.FOOD, 6, dani);

        assertThat(edited.message()).isEqualTo("Pizza Saturday");
        assertThat(edited.capacity()).isEqualTo(6);
        assertThat(ofType(alex, NotificationType.WANT_UPDATED)).hasSize(1);
    }

    @Test
    void aClosedWantCanBeCancelled() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(pizza, dani);

        wantService.cancel(pizza, dani);

        assertThat(ofType(alex, NotificationType.WANT_CANCELLED)).hasSize(1);
        assertThat(wantService.findAll(dani)).extracting(WantResponse::id).doesNotContain(pizza);
    }
}
