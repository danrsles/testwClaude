package com.example.demo.services;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import com.example.demo.models.WantStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deadlines against a real PostgreSQL: that a want past its deadline is
 * closed before the job reaches it, and that WantService.expireDue writes
 * closed_at and tells the lineup and the owner, once.
 *
 * The scheduled job is off in tests (app.expiry.enabled=false), so it never
 * races these; each test calls expireDue itself. That run is global, so a
 * test asserts only on its own wants. The API refuses a past deadline, so
 * deadlines are moved into the past with SQL (backdate). Set up like
 * WantLifecycleIT: no test transactions, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantExpiryIT {

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
    private TransactionTemplate transaction;

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

    private static Instant inAnHour() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    /** A want for four with a deadline an hour away. */
    private Long dated(ClerkIdentity owner, String message) {
        return wantService.create(message, Category.FOOD, 4, inAnHour(), owner).id();
    }

    /** Moves the want's deadline into the past, by a PostgreSQL interval such as '1 hour'. */
    private void backdate(Long wantId, String ago) {
        jdbc.update("update wants set expires_at = now() - cast(? as interval) where id = ?", ago, wantId);
    }

    private Instant closedAt(Long wantId) {
        Timestamp at = jdbc.queryForObject("select closed_at from wants where id = ?", Timestamp.class, wantId);
        return at == null ? null : at.toInstant();
    }

    /**
     * The database's time. closed_at comes from PostgreSQL's now(), so it is
     * compared with this, never the JVM's clock: on Windows, Docker's VM
     * clock can drift from the host's. Each call is its own transaction, and
     * now() is when that transaction started.
     */
    private Instant dbNow() {
        return jdbc.queryForObject("select now()", Timestamp.class).toInstant();
    }

    private int seats(Long wantId) {
        return jdbc.queryForObject("select count(*) from want_signups where want_id = ?", Integer.class, wantId);
    }

    private List<Notification> about(ClerkIdentity recipient, NotificationType type, Long wantId) {
        return notificationService.mine(recipient).stream()
                .filter(n -> n.getType() == type && wantId.equals(n.getWantId()))
                .toList();
    }

    private WantStatus statusFor(ClerkIdentity who, Long wantId) {
        return wantService.findAll(who).stream().filter(w -> w.id().equals(wantId))
                .map(WantResponse::status).findFirst().orElseThrow();
    }

    @Test
    void expiringClosesWantsPastTheirDeadlineAndTellsTheLineupAndTheOwner() {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = member("sam", dani, pizza);
        ClerkIdentity jordan = follower("jordan", dani);
        backdate(pizza, "1 minute");

        wantService.expireDue();

        assertThat(closedAt(pizza)).isNotNull();
        assertThat(statusFor(alex, pizza)).isEqualTo(WantStatus.CLOSED);
        // The owner too: nobody pressed Close, so they would not otherwise know.
        for (ClerkIdentity told : List.of(alex, sam, dani)) {
            assertThat(about(told, NotificationType.WANT_EXPIRED, pizza)).singleElement().satisfies(n -> {
                assertThat(n.getActor().getId()).isEqualTo(idOf(dani));
                assertThat(n.getWantMessage()).isEqualTo("Pizza Friday");
            });
        }
        assertThat(about(jordan, NotificationType.WANT_EXPIRED, pizza)).isEmpty();
    }

    /** closed_at says when the job closed it, so a late run shows up as late rather than being hidden. */
    @Test
    void expiringRecordsWhenTheJobClosedTheWant() {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        backdate(pizza, "1 day");

        Instant before = dbNow();
        wantService.expireDue();
        Instant after = dbNow();

        assertThat(closedAt(pizza)).isBetween(before, after);
    }

    @Test
    void expiringTwiceClosesAndNotifiesOnce() {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        backdate(pizza, "1 minute");

        wantService.expireDue();
        Instant firstClose = closedAt(pizza);
        wantService.expireDue();

        assertThat(firstClose).isNotNull();
        assertThat(closedAt(pizza)).isEqualTo(firstClose);
        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
        assertThat(about(dani, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
    }

    @Test
    void expiringLeavesClosedCancelledEndedAndUndatedWantsAlone() {
        ClerkIdentity dani = person("dani");
        Long closed = dated(dani, "Closed early");
        wantService.close(closed, dani);
        Instant closedByOwner = closedAt(closed);
        Long cancelled = dated(dani, "Called off");
        wantService.cancel(cancelled, dani);
        Long ended = dated(dani, "Already happened");
        wantService.start(ended, dani);
        wantService.end(ended, dani);
        for (Long finished : List.of(closed, cancelled, ended)) {
            backdate(finished, "1 minute");
        }
        Long undated = wantService.create("No deadline", Category.FOOD, 4, null, dani).id();
        Long ahead = dated(dani, "Still to come");

        wantService.expireDue();

        for (Long untouched : List.of(closed, cancelled, ended, undated, ahead)) {
            assertThat(about(dani, NotificationType.WANT_EXPIRED, untouched)).isEmpty();
        }
        assertThat(closedAt(closed)).isEqualTo(closedByOwner);
        assertThat(closedAt(undated)).isNull();
        assertThat(closedAt(ahead)).isNull();
        assertThat(statusFor(dani, ahead)).isEqualTo(WantStatus.ACTIVE);
    }

    @Test
    void aWantReopenedWithANewDeadlineExpiresAtThatDeadline() {
        ClerkIdentity dani = person("dani");
        Long pizza = wantService.create("Pizza Friday", Category.FOOD, 4, null, dani).id();
        ClerkIdentity alex = member("alex", dani, pizza);
        wantService.close(pizza, dani);
        wantService.reopen(pizza, inAnHour(), dani);

        // Not yet: the new deadline is an hour away.
        wantService.expireDue();
        assertThat(statusFor(dani, pizza)).isEqualTo(WantStatus.ACTIVE);
        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).isEmpty();

        backdate(pizza, "1 minute");
        wantService.expireDue();

        assertThat(statusFor(dani, pizza)).isEqualTo(WantStatus.CLOSED);
        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
        assertThat(about(dani, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
    }

    /** Two runs at once, as two instances would: the guarded UPDATE flips each want for one of them only. */
    @Test
    void expiringFromTwoThreadsAtOnceNotifiesEachSeatOnce() throws Exception {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        backdate(pizza, "1 minute");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = List.of(
                    threads.submit(() -> {
                        awaitOrFail(go);
                        return wantService.expireDue();
                    }),
                    threads.submit(() -> {
                        awaitOrFail(go);
                        return wantService.expireDue();
                    }));
            go.countDown();
            for (Future<Integer> run : runs) {
                run.get(20, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
        assertThat(about(dani, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
    }

    /**
     * The owner reopens an expired want the job has not reached yet, and holds
     * its row lock. The job's UPDATE must wait, then see the deadline gone and
     * leave the want alone. Without that re-check it would close a want the
     * owner had just reopened.
     */
    @Test
    void aReopenHoldingTheLockMakesTheJobSkipTheWant() throws Exception {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        backdate(pizza, "1 minute");
        CountDownLatch reopened = new CountDownLatch(1);
        CountDownLatch releaseReopen = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> reopen = threads.submit(() -> transaction.executeWithoutResult(status -> {
                wantService.reopen(pizza, null, dani);
                reopened.countDown();
                awaitOrFail(releaseReopen);
            }));
            assertThat(reopened.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> job = threads.submit(() -> wantService.expireDue());

            // The reopen has not committed, so the job must still be waiting for the lock.
            assertThatThrownBy(() -> job.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseReopen.countDown();
            reopen.get(10, TimeUnit.SECONDS);
            job.get(10, TimeUnit.SECONDS);
        } finally {
            releaseReopen.countDown();
            threads.shutdownNow();
        }

        assertThat(statusFor(dani, pizza)).isEqualTo(WantStatus.ACTIVE);
        assertThat(closedAt(pizza)).isNull();
        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).isEmpty();
        assertThat(about(alex, NotificationType.WANT_REOPENED, pizza)).hasSize(1);
    }

    /** No lower bound on the deadline: the first run after an outage closes everything it missed. */
    @Test
    void oneRunClosesEveryWantMissedDuringDowntime() {
        ClerkIdentity dani = person("dani");
        Long yesterday = dated(dani, "Yesterday");
        Long anHourAgo = dated(dani, "An hour ago");
        Long justNow = dated(dani, "Just now");
        backdate(yesterday, "1 day");
        backdate(anHourAgo, "1 hour");
        backdate(justNow, "1 minute");

        wantService.expireDue();

        for (Long missed : List.of(yesterday, anHourAgo, justNow)) {
            assertThat(closedAt(missed)).isNotNull();
            assertThat(about(dani, NotificationType.WANT_EXPIRED, missed)).hasSize(1);
        }
    }

    /**
     * Past its deadline the want already counts as closed, so Close changes
     * nothing; the lineup hears WANT_EXPIRED from the job instead of WANT_CLOSED.
     */
    @Test
    void closingAnExpiredWantTheJobHasNotReachedChangesNothing() {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        backdate(pizza, "1 minute");

        WantResponse closed = wantService.close(pizza, dani);

        assertThat(closed.status()).isEqualTo(WantStatus.CLOSED);
        assertThat(closedAt(pizza)).isNull();
        assertThat(about(alex, NotificationType.WANT_CLOSED, pizza)).isEmpty();

        wantService.expireDue();

        assertThat(about(alex, NotificationType.WANT_EXPIRED, pizza)).hasSize(1);
        assertThat(about(alex, NotificationType.WANT_CLOSED, pizza)).isEmpty();
    }

    /** Correctness never waits for the job: closed_at is still null, and the deadline alone refuses these. */
    @Test
    void signupWithdrawalAndEditAreRefusedPastTheDeadlineBeforeTheJobRuns() {
        ClerkIdentity dani = person("dani");
        Long pizza = dated(dani, "Pizza Friday");
        ClerkIdentity alex = member("alex", dani, pizza);
        ClerkIdentity sam = follower("sam", dani);
        backdate(pizza, "1 minute");

        assertThatThrownBy(() -> signupService.signUp(pizza, sam)).isInstanceOf(WantClosedException.class);
        assertThatThrownBy(() -> signupService.withdraw(pizza, alex)).isInstanceOf(WantClosedException.class);
        assertThatThrownBy(() -> wantService.update(pizza, "Pizza Saturday", Category.FOOD, null, null, null, null,
                dani)).isInstanceOf(WantClosedException.class);

        assertThat(closedAt(pizza)).isNull();
        assertThat(seats(pizza)).isEqualTo(2);
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
