package com.example.demo.services;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who sees a want once wants can be posted to circles, against a real
 * PostgreSQL: the feed, signups and withdrawals all read the same rule.
 * A General want reaches the poster's followers; a circle's want reaches the
 * circle's owner and members, followers or not, while its poster is still in
 * the circle and the circle hasn't been deleted.
 *
 * Every test builds the same shape: dani owns "Climbing", with alex and sam
 * as members. Both follow dani; neither follows the other.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantCircleVisibilityIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private SignupService signupService;

    @Autowired
    private CircleService circleService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TransactionTemplate transaction;

    private ClerkIdentity dani;
    private ClerkIdentity alex;
    private ClerkIdentity sam;
    private Long climbing;

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

    private void follows(ClerkIdentity who, ClerkIdentity whom) {
        followService.follow(idOf(whom), who);
    }

    /** dani's "Climbing", with alex and sam in it. */
    private void climbingCircle() {
        dani = person("dani");
        alex = person("alex");
        sam = person("sam");
        follows(alex, dani);
        follows(sam, dani);
        climbing = circleService.create("Climbing", dani).getId();
        circleService.addMember(climbing, idOf(alex), dani);
        circleService.addMember(climbing, idOf(sam), dani);
    }

    private Long postToCircle(ClerkIdentity poster, String message, Long circleId) {
        return wantService.create(message, Category.GAME, 4, null, circleId, poster).id();
    }

    private Long postToGeneral(ClerkIdentity poster, String message) {
        return wantService.create(message, Category.FOOD, 4, null, poster).id();
    }

    private List<Long> feed(ClerkIdentity who) {
        return wantService.findAll(who).stream().map(WantResponse::id).toList();
    }

    private List<Notification> posted(ClerkIdentity who) {
        return notificationService.mine(who).stream()
                .filter(n -> n.getType() == NotificationType.WANT_POSTED)
                .toList();
    }

    @Test
    void aMemberSeesAndCanJoinACircleWantFromAnotherMemberTheyDoNotFollow() {
        climbingCircle();

        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);

        assertThat(feed(alex)).contains(bouldering);
        assertThat(signupService.signUp(bouldering, alex).signedUp()).isTrue();
        // The owner sees it too, though dani doesn't follow sam.
        assertThat(feed(dani)).contains(bouldering);
        assertThat(wantService.findAll(alex)).filteredOn(w -> w.id().equals(bouldering)).singleElement()
                .satisfies(w -> assertThat(w.circle().name()).isEqualTo("Climbing"));
    }

    @Test
    void aFollowerOfThePosterWhoIsNotInTheCircleDoesNotSeeIt() {
        climbingCircle();
        ClerkIdentity jordan = person("jordan");
        follows(jordan, sam);

        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);

        assertThat(feed(jordan)).doesNotContain(bouldering);
        assertThatThrownBy(() -> signupService.signUp(bouldering, jordan)).isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void aCoMemberDoesNotSeeThePostersGeneralWants() {
        // Being in a circle with sam isn't following sam.
        climbingCircle();

        Long pizza = postToGeneral(sam, "Pizza Friday");

        assertThat(feed(alex)).doesNotContain(pizza);
        assertThatThrownBy(() -> signupService.signUp(pizza, alex)).isInstanceOf(WantNotFoundException.class);
    }

    @Test
    void outsidersGet404EvenOnAClosedCircleWant() {
        // A 409 "closed" would tell an outsider the want exists.
        climbingCircle();
        ClerkIdentity stranger = person("stranger");
        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);
        wantService.close(bouldering, sam);

        assertThatThrownBy(() -> signupService.signUp(bouldering, stranger))
                .isInstanceOf(WantNotFoundException.class);
        assertThatThrownBy(() -> signupService.signUp(bouldering, alex)).isInstanceOf(WantClosedException.class);
    }

    @Test
    void removingThePosterHidesTheirWantsFromTheCircle() {
        climbingCircle();
        ClerkIdentity jordan = person("jordan");
        follows(jordan, dani);
        circleService.addMember(climbing, idOf(jordan), dani);
        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);
        signupService.signUp(bouldering, alex);

        circleService.removeMember(climbing, idOf(sam), dani);

        assertThat(feed(jordan)).doesNotContain(bouldering);
        assertThat(feed(alex)).doesNotContain(bouldering);
        assertThat(feed(sam)).contains(bouldering);
        // Seated people keep their seat, and can still give it up.
        assertThat(signupService.withdraw(bouldering, alex).signedUp()).isFalse();
    }

    @Test
    void deletingTheCircleHidesItsWantsFromAllButThePoster() {
        climbingCircle();
        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);
        signupService.signUp(bouldering, alex);

        circleService.delete(climbing, dani);

        assertThat(feed(dani)).doesNotContain(bouldering);
        assertThat(feed(alex)).doesNotContain(bouldering);
        assertThat(feed(sam)).contains(bouldering);
        assertThat(signupService.withdraw(bouldering, alex).signedUp()).isFalse();
    }

    @Test
    void postingRacingARemovalIsNotFound() throws Exception {
        climbingCircle();
        CountDownLatch removed = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> removal = threads.submit(() -> transaction.executeWithoutResult(status -> {
                circleService.removeMember(climbing, idOf(sam), dani);
                removed.countDown();
                awaitOrFail(releaseRemoval);
            }));
            assertThat(removed.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Long> post = threads.submit(() -> postToCircle(sam, "Bouldering Saturday", climbing));

            // The removal holds the circle's row lock, so the post waits before checking membership.
            assertThatThrownBy(() -> post.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseRemoval.countDown();
            removal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> post.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CircleNotFoundException.class);
        } finally {
            releaseRemoval.countDown();
            threads.shutdownNow();
        }

        assertThat(feed(sam)).isEmpty();
    }

    @Test
    void postingRacingADeleteIsNotFound() throws Exception {
        climbingCircle();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> delete = threads.submit(() -> transaction.executeWithoutResult(status -> {
                circleService.delete(climbing, dani);
                deleted.countDown();
                awaitOrFail(releaseDelete);
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Long> post = threads.submit(() -> postToCircle(alex, "Bouldering Saturday", climbing));

            assertThatThrownBy(() -> post.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseDelete.countDown();
            delete.get(10, TimeUnit.SECONDS);
            // Once the delete commits, the post's locked read finds the circle gone.
            assertThatThrownBy(() -> post.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CircleNotFoundException.class);
        } finally {
            releaseDelete.countDown();
            threads.shutdownNow();
        }

        assertThat(feed(alex)).isEmpty();
    }

    @Test
    void editingWithTheSameCircleAfterLeavingSucceeds() {
        climbingCircle();
        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);
        circleService.removeMember(climbing, idOf(sam), sam);

        WantResponse edited = wantService.update(bouldering, "Bouldering Sunday", Category.GAME, null, null, null,
                null, climbing, null, sam);

        assertThat(edited.message()).isEqualTo("Bouldering Sunday");
        assertThat(edited.circle().id()).isEqualTo(climbing);
    }

    @Test
    void movingGeneralToCircleToGeneralAnnouncesEachPersonOnce() {
        climbingCircle();
        ClerkIdentity jordan = person("jordan");
        follows(jordan, sam);
        Long pizza = postToGeneral(sam, "Pizza Friday");

        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, null, null, null, climbing, null, sam);
        wantService.update(pizza, "Pizza Friday", Category.FOOD, null, null, null, null, null, true, sam);

        // jordan heard as sam's follower, then alex and dani as the circle; nobody twice.
        assertThat(posted(jordan)).hasSize(1);
        assertThat(posted(alex)).hasSize(1);
        assertThat(posted(dani)).hasSize(1);
        assertThat(posted(sam)).isEmpty();
    }

    @Test
    void aFollowerWhoMutedThePosterStillGetsTheirCircleWants() {
        // Muting is about following; a circle's wants reach all its members.
        climbingCircle();
        follows(alex, sam);
        followService.mute(idOf(sam), alex);

        Long bouldering = postToCircle(sam, "Bouldering Saturday", climbing);

        assertThat(posted(alex)).singleElement().extracting(Notification::getWantId).isEqualTo(bouldering);
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
