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
 * Starting and ending against a real PostgreSQL. Happening is not a status:
 * it says the want is live, while status still says who can join, so a
 * happening want can be active (still joinable) or closed (lineup fixed).
 * Ending is final. Set up like WantLifecycleIT: nothing is wrapped in a test
 * transaction, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantHappeningIT {

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

    /** The want as it appears in the viewer's feed. */
    private WantResponse inFeed(Long wantId, ClerkIdentity viewer) {
        return wantService.findAll(viewer).stream().filter(w -> w.id().equals(wantId)).findFirst().orElseThrow();
    }

    @Test
    void startingNotifiesTheLineupButNotTheOwner() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = member("sam", dani, pizza);
        ClerkIdentity jordan = follower("jordan", dani);

        WantResponse started = wantService.start(pizza, dani);

        assertThat(started.happening()).isTrue();
        assertThat(started.status()).isEqualTo(WantStatus.ACTIVE);
        for (ClerkIdentity inLineup : List.of(alex, sam)) {
            assertThat(ofType(inLineup, NotificationType.WANT_STARTED)).singleElement().satisfies(n -> {
                assertThat(n.getActor().getId()).isEqualTo(idOf(dani));
                assertThat(n.getWantId()).isEqualTo(pizza);
                assertThat(n.getWantMessage()).isEqualTo("Pizza Friday");
            });
        }
        assertThat(ofType(dani, NotificationType.WANT_STARTED)).isEmpty();
        assertThat(ofType(jordan, NotificationType.WANT_STARTED)).isEmpty();
    }

    @Test
    void startingTwiceNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);

        wantService.start(pizza, dani);
        WantResponse again = wantService.start(pizza, dani);

        assertThat(again.happening()).isTrue();
        assertThat(ofType(alex, NotificationType.WANT_STARTED)).hasSize(1);
    }

    /** Starting says it is live; it does not fix the lineup. Closing does that. */
    @Test
    void aHappeningActiveWantStillTakesSignups() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = follower("sam", dani);
        wantService.start(pizza, dani);

        signupService.signUp(pizza, sam);
        signupService.withdraw(pizza, alex);

        assertThat(seats(pizza)).isEqualTo(2);
        WantResponse seen = inFeed(pizza, sam);
        assertThat(seen.status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(seen.happening()).isTrue();
        assertThat(seen.signedUp()).isTrue();
    }

    @Test
    void aClosedWantCanStartAndItsLineupStaysFixed() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = follower("sam", dani);
        wantService.close(pizza, dani);

        WantResponse started = wantService.start(pizza, dani);

        assertThat(started.status()).isEqualTo(WantStatus.CLOSED);
        assertThat(started.happening()).isTrue();
        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantClosedException.class);
        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(alex, NotificationType.WANT_STARTED)).hasSize(1);
    }

    @Test
    void aHappeningActiveWantCanBeRenamed() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.start(pizza, dani);

        WantResponse renamed = wantService.update(pizza, "Pizza at Luigi's", Category.FOOD, null, null, dani);

        assertThat(renamed.message()).isEqualTo("Pizza at Luigi's");
        assertThat(renamed.happening()).isTrue();
        assertThat(ofType(alex, NotificationType.WANT_UPDATED)).singleElement()
                .satisfies(n -> assertThat(n.getPreviousMessage()).isEqualTo("Pizza Friday"));
    }

    @Test
    void endingNotifiesTheLineupButNotTheOwner() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity jordan = follower("jordan", dani);
        wantService.start(pizza, dani);

        WantResponse ended = wantService.end(pizza, dani);

        assertThat(ended.status()).isEqualTo(WantStatus.ENDED);
        assertThat(ended.happening()).isFalse();
        assertThat(ofType(alex, NotificationType.WANT_ENDED)).singleElement().satisfies(n -> {
            assertThat(n.getActor().getId()).isEqualTo(idOf(dani));
            assertThat(n.getWantId()).isEqualTo(pizza);
            assertThat(n.getWantMessage()).isEqualTo("Pizza Friday");
        });
        assertThat(ofType(dani, NotificationType.WANT_ENDED)).isEmpty();
        assertThat(ofType(jordan, NotificationType.WANT_ENDED)).isEmpty();
    }

    @Test
    void endingTwiceNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.start(pizza, dani);

        wantService.end(pizza, dani);
        WantResponse again = wantService.end(pizza, dani);

        assertThat(again.status()).isEqualTo(WantStatus.ENDED);
        assertThat(ofType(alex, NotificationType.WANT_ENDED)).hasSize(1);
    }

    /** What never started cannot end: the owner closes or cancels it instead. */
    @Test
    void onlyAHappeningWantCanBeEnded() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        Long tacos = want(dani, "Tacos", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(tacos, dani);

        assertThatThrownBy(() -> wantService.end(pizza, dani)).isInstanceOf(WantNotHappeningException.class);
        assertThatThrownBy(() -> wantService.end(tacos, dani)).isInstanceOf(WantNotHappeningException.class);

        assertThat(inFeed(pizza, dani).status()).isEqualTo(WantStatus.ACTIVE);
        assertThat(inFeed(tacos, dani).status()).isEqualTo(WantStatus.CLOSED);
        assertThat(ofType(alex, NotificationType.WANT_ENDED)).isEmpty();
    }

    @Test
    void anEndedWantStaysInTheFeedAsEnded() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.start(pizza, dani);
        wantService.end(pizza, dani);

        for (ClerkIdentity viewer : List.of(dani, alex)) {
            assertThat(inFeed(pizza, viewer).status()).isEqualTo(WantStatus.ENDED);
            assertThat(wantService.findByCategory(Category.FOOD, viewer)).extracting(WantResponse::id).contains(pizza);
        }
    }

    /** Ending is final, like cancelling, but the want stays in the feed. */
    @Test
    void anEndedWantCannotBeEditedClosedReopenedStartedOrCancelled() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.start(pizza, dani);
        wantService.end(pizza, dani);

        assertThatThrownBy(() -> wantService.update(pizza, "Pizza again", Category.FOOD, null, null, dani))
                .isInstanceOf(WantEndedException.class);
        assertThatThrownBy(() -> wantService.close(pizza, dani)).isInstanceOf(WantEndedException.class);
        assertThatThrownBy(() -> wantService.reopen(pizza, dani)).isInstanceOf(WantEndedException.class);
        assertThatThrownBy(() -> wantService.start(pizza, dani)).isInstanceOf(WantEndedException.class);
        assertThatThrownBy(() -> wantService.cancel(pizza, dani)).isInstanceOf(WantEndedException.class);

        WantResponse seen = inFeed(pizza, alex);
        assertThat(seen.status()).isEqualTo(WantStatus.ENDED);
        assertThat(seen.message()).isEqualTo("Pizza Friday");
        assertThat(ofType(alex, NotificationType.WANT_UPDATED)).isEmpty();
        assertThat(ofType(alex, NotificationType.WANT_CANCELLED)).isEmpty();
    }

    @Test
    void noSignupOrWithdrawalWhenEnded() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = follower("sam", dani);
        wantService.start(pizza, dani);
        wantService.end(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantEndedException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, alex)).isInstanceOf(WantEndedException.class);

        assertThat(seats(pizza)).isEqualTo(2);
    }

    /** The 409 would tell a stranger the want exists, so they get the same 404 as for an active one. */
    @Test
    void anEndedWantIsNotFoundForSomeoneWhoCannotSeeIt() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        Long pizza = want(dani, "Pizza Friday", 4);
        wantService.start(pizza, dani);
        wantService.end(pizza, dani);

        assertThatThrownBy(() -> signupService.signUp(pizza, stranger)).isInstanceOf(WantNotFoundException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, stranger)).isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void aHappeningWantCanBeCancelled() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.start(pizza, dani);

        wantService.cancel(pizza, dani);

        assertThat(ofType(alex, NotificationType.WANT_CANCELLED)).hasSize(1);
        assertThat(wantService.findAll(alex)).extracting(WantResponse::id).doesNotContain(pizza);
    }

    @Test
    void aCancelledWantCannotBeStartedOrEnded() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        Long tacos = want(dani, "Tacos", 4);
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.cancel(pizza, dani);
        wantService.start(tacos, dani);
        wantService.cancel(tacos, dani);

        assertThatThrownBy(() -> wantService.start(pizza, dani)).isInstanceOf(WantCancelledException.class);
        assertThatThrownBy(() -> wantService.end(tacos, dani)).isInstanceOf(WantCancelledException.class);

        assertThat(ofType(alex, NotificationType.WANT_STARTED)).isEmpty();
        assertThat(ofType(alex, NotificationType.WANT_ENDED)).isEmpty();
    }
}
