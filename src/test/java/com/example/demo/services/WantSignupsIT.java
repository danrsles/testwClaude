package com.example.demo.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.SignupEventDetails;
import com.example.demo.dto.UserSummary;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

/**
 * Capacity and signups against a real PostgreSQL: the row lock that decides
 * who gets the last seat, the constraints that make signing up idempotent,
 * and the transactions that keep a signup and its notification together.
 * Set up like FollowNotificationsIT: nothing is wrapped in a test
 * transaction, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantSignupsIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private SignupService signupService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    /** Real unless a test says otherwise; Spring resets it after each test. */
    @MockitoSpyBean
    private NotificationService notificationService;

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

    private Long want(ClerkIdentity owner, String message, int capacity) {
        return wantService.create(message, Category.FOOD, capacity, null, owner).id();
    }

    private int seats(Long wantId) {
        return jdbc.queryForObject("select count(*) from want_signups where want_id = ?", Integer.class, wantId);
    }

    private List<Notification> ofType(ClerkIdentity recipient, NotificationType type) {
        return notificationService.mine(recipient).stream().filter(n -> n.getType() == type).toList();
    }

    @Test
    void creatingAWantSeatsTheOwner() {
        ClerkIdentity dani = person("dani");

        Long pizza = want(dani, "Pizza Friday", 4);

        assertThat(seats(pizza)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select user_id from want_signups where want_id = ?", Long.class, pizza))
                .isEqualTo(idOf(dani));
    }

    /**
     * Every row of the feed says how full the want is and whether you are in,
     * so the screen can show "2 of 4" and the right button without asking again.
     */
    @Test
    void theFeedShowsSeatsTakenAndWhetherYouAreSignedUp() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        WantResponse created = wantService.create("Pizza Friday", Category.FOOD, 4, null, dani);
        Long pizza = created.id();
        Long tacos = want(dani, "Tacos", 2);
        signupService.signUp(pizza, alex);

        assertThat(created.seatsTaken()).isEqualTo(1);
        assertThat(created.signedUp()).isTrue();
        assertThat(wantService.findAll(alex))
                .extracting(WantResponse::id, WantResponse::capacity, WantResponse::seatsTaken, WantResponse::signedUp)
                .contains(tuple(pizza, 4, 2, true), tuple(tacos, 2, 1, false));
        assertThat(wantService.findAll(sam))
                .extracting(WantResponse::id, WantResponse::seatsTaken, WantResponse::signedUp)
                .contains(tuple(pizza, 2, false));
        assertThat(wantService.findByCategory(Category.FOOD, dani))
                .extracting(WantResponse::id, WantResponse::seatsTaken, WantResponse::signedUp)
                .contains(tuple(pizza, 2, true), tuple(tacos, 1, true));
    }

    /** Each row of the feed carries its lineup, pictures included, so the screen can show who is going. */
    @Test
    void theFeedShowsEachWantsLineupWithPictures() {
        ClerkIdentity dani = person("dani");
        String picture = "https://img.clerk.com/alex.png";
        ClerkIdentity alex = new ClerkIdentity("user_alex_" + UUID.randomUUID().toString().substring(0, 8),
                "alex_" + UUID.randomUUID().toString().substring(0, 8), null, null, picture);
        followService.follow(idOf(dani), alex);
        Long pizza = want(dani, "Pizza Friday", 4);
        Long tacos = want(dani, "Tacos", 2);
        signupService.signUp(pizza, alex);

        List<WantResponse> feed = wantService.findAll(alex);

        WantResponse pizzaRow = feed.stream().filter(w -> w.id().equals(pizza)).findFirst().orElseThrow();
        WantResponse tacosRow = feed.stream().filter(w -> w.id().equals(tacos)).findFirst().orElseThrow();
        assertThat(pizzaRow.lineup())
                .extracting(UserSummary::id, UserSummary::username, UserSummary::imageUrl)
                .containsExactly(tuple(idOf(dani), dani.username(), null), tuple(idOf(alex), alex.username(), picture));
        assertThat(tacosRow.lineup()).extracting(UserSummary::id).containsExactly(idOf(dani));
    }

    @Test
    void creatingAWantNotifiesOnlyTheOwnerThatItWasCreated() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);

        Long pizza = want(dani, "Pizza Friday", 4);

        List<Notification> created = ofType(dani, NotificationType.WANT_CREATED);
        assertThat(created).hasSize(1);
        assertThat(created.getFirst().getActor().getId()).isEqualTo(idOf(dani));
        assertThat(created.getFirst().getWantId()).isEqualTo(pizza);
        assertThat(created.getFirst().getWantMessage()).isEqualTo("Pizza Friday");
        // Followers hear of it as WANT_POSTED instead; see WantAnnounceIT.
        assertThat(ofType(alex, NotificationType.WANT_CREATED)).isEmpty();
    }

    @Test
    void othersCanSignUpAndWithdraw() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        SignupEventDetails joined = signupService.signUp(pizza, alex);

        assertThat(joined.signedUp()).isTrue();
        assertThat(joined.seatsTaken()).isEqualTo(2);
        assertThat(joined.capacity()).isEqualTo(4);
        assertThat(joined.message()).isEqualTo("Pizza Friday");
        assertThat(joined.lineup()).extracting(UserSummary::username).containsExactly(dani.username(), alex.username());

        SignupEventDetails left = signupService.withdraw(pizza, alex);

        assertThat(left.signedUp()).isFalse();
        assertThat(left.seatsTaken()).isEqualTo(1);
        assertThat(left.lineup()).extracting(UserSummary::username).containsExactly(dani.username());
        assertThat(seats(pizza)).isEqualTo(1);
    }

    /** The lineup carries each person's picture, so whoever joins sees theirs at once. */
    @Test
    void theLineupShowsEachPersonsPicture() {
        ClerkIdentity dani = person("dani");
        String picture = "https://img.clerk.com/alex.png";
        ClerkIdentity alex = new ClerkIdentity("user_alex_" + UUID.randomUUID().toString().substring(0, 8),
                "alex_" + UUID.randomUUID().toString().substring(0, 8), null, null, picture);
        followService.follow(idOf(dani), alex);
        Long pizza = want(dani, "Pizza Friday", 4);

        SignupEventDetails joined = signupService.signUp(pizza, alex);

        assertThat(joined.lineup()).extracting(UserSummary::id).containsExactly(idOf(dani), idOf(alex));
        // dani came from a token without the picture claim, so has none yet.
        assertThat(joined.lineup()).extracting(UserSummary::imageUrl).containsExactly(null, picture);
    }

    @Test
    void signupsStopAtCapacity() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        Long pizza = want(dani, "Pizza for two", 2);
        signupService.signUp(pizza, alex);

        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantFullException.class);

        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    /**
     * The last-seat race, made deterministic. alex's signup takes the want's
     * row lock and holds its transaction open; sam's signup must wait for it,
     * then find the want full. Without the lock, sam would not wait, would
     * count only the committed seats, and would get in too.
     */
    @Test
    void aSecondSignupWaitsForTheFirstAndFindsTheWantFull() throws Exception {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        Long pizza = want(dani, "Pizza for two", 2);
        CountDownLatch alexSignedUp = new CountDownLatch(1);
        CountDownLatch releaseAlex = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transaction.executeWithoutResult(status -> {
                signupService.signUp(pizza, alex);
                alexSignedUp.countDown();
                awaitOrFail(releaseAlex);
            }));
            assertThat(alexSignedUp.await(10, TimeUnit.SECONDS)).isTrue();

            Future<SignupEventDetails> second = threads.submit(() -> signupService.signUp(pizza, sam));

            // alex has not committed, so sam must still be waiting for the lock.
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseAlex.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(WantFullException.class);
        } finally {
            releaseAlex.countDown();
            threads.shutdownNow();
        }

        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    /**
     * Five people at once for one seat. Fewer threads than the connection
     * pool, so they wait on the row lock rather than for a connection.
     */
    @Test
    void onlyOneOfFiveSimultaneousSignupsGetsTheLastSeat() throws Exception {
        ClerkIdentity dani = person("dani");
        List<ClerkIdentity> crowd = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            crowd.add(follower("fan" + i, dani));
        }
        Long pizza = want(dani, "Pizza for two", 2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(crowd.size());
        List<Future<SignupEventDetails>> attempts = new ArrayList<>();
        try {
            for (ClerkIdentity fan : crowd) {
                Callable<SignupEventDetails> attempt = () -> {
                    awaitOrFail(go);
                    return signupService.signUp(pizza, fan);
                };
                attempts.add(threads.submit(attempt));
            }
            go.countDown();

            int in = 0;
            int full = 0;
            for (Future<SignupEventDetails> attempt : attempts) {
                try {
                    attempt.get(20, TimeUnit.SECONDS);
                    in++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(WantFullException.class);
                    full++;
                }
            }
            assertThat(in).isEqualTo(1);
            assertThat(full).isEqualTo(4);
        } finally {
            threads.shutdownNow();
        }

        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    @Test
    void aMemberRepeatingSignupOnAFullWantGetsTheirDetails() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza for two", 2);
        signupService.signUp(pizza, alex);

        SignupEventDetails again = signupService.signUp(pizza, alex);
        // The owner holds a seat from the start, so the same applies to them.
        SignupEventDetails owner = signupService.signUp(pizza, dani);

        assertThat(again.signedUp()).isTrue();
        assertThat(owner.signedUp()).isTrue();
        assertThat(owner.lineup()).extracting(UserSummary::username).containsExactly(dani.username(), alex.username());
        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    @Test
    void signingUpTwiceTakesOneSeatAndNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        signupService.signUp(pizza, alex);
        SignupEventDetails again = signupService.signUp(pizza, alex);

        assertThat(again.seatsTaken()).isEqualTo(2);
        assertThat(seats(pizza)).isEqualTo(2);
        assertThat(ofType(dani, NotificationType.WANT_SIGNUP)).hasSize(1);
    }

    @Test
    void withdrawingFreesASeat() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        Long pizza = want(dani, "Pizza for two", 2);
        signupService.signUp(pizza, alex);

        signupService.withdraw(pizza, alex);
        SignupEventDetails samIn = signupService.signUp(pizza, sam);

        assertThat(samIn.lineup()).extracting(UserSummary::username).containsExactly(dani.username(), sam.username());
    }

    @Test
    void withdrawingWhenNotSignedUpDoesNothing() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        SignupEventDetails details = signupService.withdraw(pizza, alex);

        assertThat(details.signedUp()).isFalse();
        assertThat(seats(pizza)).isEqualTo(1);
        assertThat(ofType(dani, NotificationType.WANT_WITHDRAWN)).isEmpty();
    }

    @Test
    void signingUpNotifiesTheOwner() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        signupService.signUp(pizza, alex);

        List<Notification> signups = ofType(dani, NotificationType.WANT_SIGNUP);
        assertThat(signups).hasSize(1);
        assertThat(signups.getFirst().getActor().getId()).isEqualTo(idOf(alex));
        assertThat(signups.getFirst().getWantId()).isEqualTo(pizza);
        assertThat(signups.getFirst().getWantMessage()).isEqualTo("Pizza Friday");
        // The one signing up hears nothing about their own signup.
        assertThat(ofType(alex, NotificationType.WANT_SIGNUP)).isEmpty();
    }

    @Test
    void withdrawingNotifiesTheOwner() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        signupService.withdraw(pizza, alex);

        List<Notification> withdrawals = ofType(dani, NotificationType.WANT_WITHDRAWN);
        assertThat(withdrawals).hasSize(1);
        assertThat(withdrawals.getFirst().getActor().getId()).isEqualTo(idOf(alex));
        assertThat(withdrawals.getFirst().getWantId()).isEqualTo(pizza);
    }

    @Test
    void onlyFollowersCanSignUp() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        Long pizza = want(dani, "Pizza Friday", 4);

        // Not in their feed, so to them it does not exist.
        assertThatThrownBy(() -> signupService.signUp(pizza, stranger)).isInstanceOf(WantNotFoundException.class);
        assertThat(seats(pizza)).isEqualTo(1);

        followService.follow(idOf(dani), stranger);

        assertThat(signupService.signUp(pizza, stranger).signedUp()).isTrue();
    }

    @Test
    void notificationKeepsTheWantMessageAfterAnEdit() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, null, null, null, dani);

        assertThat(ofType(dani, NotificationType.WANT_SIGNUP).getFirst().getWantMessage())
                .isEqualTo("Pizza Friday");
    }

    @Test
    void renamingNotifiesTheLineupWithTheOldAndNewName() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        ClerkIdentity onlooker = follower("onlooker", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);
        signupService.signUp(pizza, sam);

        wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, null, null, null, dani);

        for (ClerkIdentity member : List.of(alex, sam)) {
            List<Notification> updates = ofType(member, NotificationType.WANT_UPDATED);
            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst().getActor().getId()).isEqualTo(idOf(dani));
            assertThat(updates.getFirst().getWantId()).isEqualTo(pizza);
            assertThat(updates.getFirst().getPreviousMessage()).isEqualTo("Pizza Friday");
            assertThat(updates.getFirst().getWantMessage()).isEqualTo("Pizza Saturday");
        }
        // Following dani is not enough: only the lineup hears about edits.
        assertThat(ofType(onlooker, NotificationType.WANT_UPDATED)).isEmpty();
    }

    @Test
    void changingTheCategoryNotifiesTheLineup() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Friday", Category.MOVIE, null, null, null, null, dani);

        List<Notification> updates = ofType(alex, NotificationType.WANT_UPDATED);
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().getWantMessage()).isEqualTo("Pizza Friday");
        // The name did not change, so there is no old name to show.
        assertThat(updates.getFirst().getPreviousMessage()).isNull();
    }

    @Test
    void theOwnerIsNotNotifiedOfTheirOwnEdit() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, null, null, null, dani);

        assertThat(ofType(dani, NotificationType.WANT_UPDATED)).isEmpty();
    }

    @Test
    void raisingCapacityOrSavingUnchangedNotifiesNobody() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Friday", Category.FOOD, 6, null, null, null, dani);
        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, null, null, null, dani);

        assertThat(ofType(alex, NotificationType.WANT_UPDATED)).isEmpty();
    }

    /** Each edit is its own notification; grouping them is for the screen to decide. */
    @Test
    void everyRenameIsItsOwnNotification() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Sat", Category.FOOD, null, null, null, null, dani);
        wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, null, null, null, dani);

        assertThat(ofType(alex, NotificationType.WANT_UPDATED))
                .extracting(Notification::getPreviousMessage, Notification::getWantMessage)
                .containsExactly(
                        tuple("Pizza Sat", "Pizza Saturday"),
                        tuple("Pizza Friday", "Pizza Sat"));
    }

    @Test
    void raisingCapacityOpensNewSeats() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        Long pizza = want(dani, "Pizza for two", 2);
        signupService.signUp(pizza, alex);
        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantFullException.class);

        wantService.update(pizza, "Pizza for three", Category.FOOD, 3, null, null, null, dani);

        SignupEventDetails samIn = signupService.signUp(pizza, sam);
        assertThat(samIn.capacity()).isEqualTo(3);
        assertThat(samIn.seatsTaken()).isEqualTo(3);
    }

    /** Created with no limit, so the column must take a null capacity. */
    @Test
    void anOpenInviteTakesEveryoneWhoCanSeeIt() {
        ClerkIdentity dani = person("dani");
        List<ClerkIdentity> friends = List.of(follower("alex", dani), follower("sam", dani), follower("jordan", dani));
        Long games = wantService.create("Board games", Category.GAME, null, null, dani).id();

        for (ClerkIdentity friend : friends) {
            signupService.signUp(games, friend);
        }

        assertThat(seats(games)).isEqualTo(4);
        assertThat(wantService.findAll(dani))
                .filteredOn(w -> w.id().equals(games))
                .extracting(WantResponse::capacity, WantResponse::seatsTaken)
                .containsExactly(tuple(null, 4));
    }

    @Test
    void anOpenInviteStillOnlyTakesPeopleWhoCanSeeIt() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        Long games = wantService.create("Board games", Category.GAME, null, null, dani).id();

        assertThatThrownBy(() -> signupService.signUp(games, stranger)).isInstanceOf(WantNotFoundException.class);
        assertThat(seats(games)).isEqualTo(1);
    }

    @Test
    void aLimitedWantOpenedLaterTakesMoreThanItsOldLimit() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 1);
        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(WantFullException.class);

        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, true, null, null, dani);

        SignupEventDetails alexIn = signupService.signUp(pizza, alex);
        assertThat(alexIn.capacity()).isNull();
        assertThat(alexIn.seatsTaken()).isEqualTo(2);
    }

    /** The limit is counted from the real lineup, under the row lock signups take too. */
    @Test
    void anOpenInviteLimitedLaterIsFullAtItsLineup() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);
        ClerkIdentity jordan = follower("jordan", dani);
        Long games = wantService.create("Board games", Category.GAME, null, null, dani).id();
        signupService.signUp(games, alex);
        signupService.signUp(games, sam);

        WantResponse limited = wantService.update(games, "Board games", Category.GAME, null, false, null, null, dani);

        assertThat(limited.capacity()).isEqualTo(3);
        assertThatThrownBy(() -> signupService.signUp(games, jordan)).isInstanceOf(WantFullException.class);
        assertThat(seats(games)).isEqualTo(3);
    }

    /**
     * Also proves V15 widened notifications_type_check: without it, the
     * first notification's insert fails and so does the edit.
     */
    @Test
    void openingAndLimitingNotifyTheLineupOnceEach() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        signupService.signUp(pizza, alex);

        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, true, null, null, dani);
        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, true, null, null, dani);
        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, false, null, null, dani);
        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, false, null, null, dani);

        assertThat(ofType(alex, NotificationType.WANT_OPEN_INVITE)).hasSize(1);
        assertThat(ofType(alex, NotificationType.WANT_LIMITED)).hasSize(1);
        assertThat(ofType(dani, NotificationType.WANT_OPEN_INVITE)).isEmpty();
        assertThat(ofType(dani, NotificationType.WANT_LIMITED)).isEmpty();
    }

    @Test
    void theSignupIsUndoneWhenItsNotificationFails() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);
        willThrow(new IllegalStateException("notifications are down"))
                .given(notificationService).notify(any(), any(), eq(NotificationType.WANT_SIGNUP), any());

        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(IllegalStateException.class);

        // One transaction: the seat taken before the failure rolled back with it.
        assertThat(seats(pizza)).isEqualTo(1);
    }

    /** V12 seats the owner of every want that existed before signups: here, the seeded ones. */
    @Test
    void existingWantsHaveCapacityOneAndTheirOwnerSeated() {
        Integer seeded = jdbc.queryForObject("""
                select count(*) from wants w join users u on u.id = w.user_id
                where u.clerk_id is null""", Integer.class);
        Integer seatedAlone = jdbc.queryForObject("""
                select count(*) from wants w join users u on u.id = w.user_id
                where u.clerk_id is null
                  and w.capacity = 1
                  and (select count(*) from want_signups s where s.want_id = w.id) = 1
                  and exists (select 1 from want_signups s where s.want_id = w.id and s.user_id = w.user_id)""",
                Integer.class);

        assertThat(seeded).isPositive();
        assertThat(seatedAlone).isEqualTo(seeded);
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
