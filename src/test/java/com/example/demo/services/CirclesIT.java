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
import com.example.demo.models.Circle;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.FollowRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Circles against a real PostgreSQL: the keys that tie membership to
 * following, the locks that make circle changes take turns, and
 * CIRCLE_ADDED. Set up like WantSignupsIT: nothing is wrapped in a test
 * transaction, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CirclesIT {

    @Autowired
    private CircleService circleService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private FollowRepository followRepository;

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

    /** Someone who follows the owner, so the owner can add them. */
    private ClerkIdentity follower(String name, ClerkIdentity owner) {
        ClerkIdentity who = person(name);
        followService.follow(idOf(owner), who);
        return who;
    }

    /** A follower of the owner, added to the circle. */
    private ClerkIdentity member(String name, ClerkIdentity owner, Long circleId) {
        ClerkIdentity who = follower(name, owner);
        circleService.addMember(circleId, idOf(who), owner);
        return who;
    }

    private List<String> names(ClerkIdentity who) {
        return circleService.mine(who).stream().map(Circle::getName).toList();
    }

    private List<Long> memberIds(Long circleId, ClerkIdentity who) {
        return circleService.members(circleId, who).stream().map(User::getId).toList();
    }

    private List<Notification> added(ClerkIdentity who) {
        return notificationService.mine(who).stream()
                .filter(n -> n.getType() == NotificationType.CIRCLE_ADDED)
                .toList();
    }

    @Test
    void createsListsRenamesAndDeletes() {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        circleService.create("Board games", dani);

        circleService.rename(climbing, "Bouldering", dani);
        assertThat(names(dani)).containsExactly("Bouldering", "Board games");

        circleService.delete(climbing, dani);
        assertThat(names(dani)).containsExactly("Board games");
        assertThatThrownBy(() -> circleService.members(climbing, dani)).isInstanceOf(CircleNotFoundException.class);
        // The row stays, archived, because wants will point at it.
        assertThat(jdbc.queryForObject("select archived_at is not null from circles where id = ?", Boolean.class,
                climbing)).isTrue();
    }

    @Test
    void membersSeeTheCircleAndEachOther() {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        ClerkIdentity alex = member("alex", dani, climbing);
        ClerkIdentity sam = member("sam", dani, climbing);
        ClerkIdentity stranger = person("stranger");

        assertThat(circleService.mine(alex)).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("Climbing");
            assertThat(c.getOwner().getId()).isEqualTo(idOf(dani));
        });
        assertThat(memberIds(climbing, sam)).containsExactly(idOf(alex), idOf(sam));
        assertThat(names(stranger)).isEmpty();
        assertThatThrownBy(() -> circleService.members(climbing, stranger))
                .isInstanceOf(CircleNotFoundException.class);
    }

    @Test
    void aMemberCanLeave() {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        ClerkIdentity alex = member("alex", dani, climbing);

        circleService.removeMember(climbing, idOf(alex), alex);

        assertThat(names(alex)).isEmpty();
        assertThat(memberIds(climbing, dani)).isEmpty();
    }

    @Test
    void aDeletedCirclesNameCanBeReused() {
        ClerkIdentity dani = person("dani");
        Long first = circleService.create("Climbing", dani).getId();
        circleService.delete(first, dani);

        Long second = circleService.create("climbing", dani).getId();

        assertThat(second).isNotEqualTo(first);
        assertThat(names(dani)).containsExactly("climbing");
    }

    @Test
    void twoCreatesWithTheSameNameAtOnceMakeOne() throws Exception {
        ClerkIdentity dani = person("dani");
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transaction.executeWithoutResult(status -> {
                circleService.create("Climbing", dani);
                firstCreated.countDown();
                awaitOrFail(releaseFirst);
            }));
            assertThat(firstCreated.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Circle> second = threads.submit(() -> circleService.create("climbing", dani));

            // The first holds dani's row lock, so the second waits rather than checking too early.
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DuplicateCircleNameException.class);
        } finally {
            releaseFirst.countDown();
            threads.shutdownNow();
        }

        assertThat(names(dani)).containsExactly("Climbing");
    }

    @Test
    void addingSomeoneWhoDoesNotFollowYouIsRefused() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        Long climbing = circleService.create("Climbing", dani).getId();

        assertThatThrownBy(() -> circleService.addMember(climbing, idOf(stranger), dani))
                .isInstanceOf(NotYourFollowerException.class);
        assertThatThrownBy(() -> circleService.addMember(climbing, 999_999_999L, dani))
                .isInstanceOf(NotYourFollowerException.class);

        // A clean refusal, not a constraint error: the next call works.
        ClerkIdentity alex = member("alex", dani, climbing);
        assertThat(memberIds(climbing, dani)).containsExactly(idOf(alex));
    }

    @Test
    void addingRacingAnUnfollowGivesA400() throws Exception {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long climbing = circleService.create("Climbing", dani).getId();
        CountDownLatch unfollowed = new CountDownLatch(1);
        CountDownLatch releaseUnfollow = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> unfollow = threads.submit(() -> transaction.executeWithoutResult(status -> {
                followService.unfollow(idOf(dani), alex);
                // Hibernate runs the DELETE at flush, normally at commit. Flushed now, so the
                // row is locked for deletion while the test holds this transaction open.
                followRepository.flush();
                unfollowed.countDown();
                awaitOrFail(releaseUnfollow);
            }));
            assertThat(unfollowed.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> add = threads.submit(() -> circleService.addMember(climbing, idOf(alex), dani));

            // The add locks alex's follow row, which the unfollow is deleting, so it waits.
            assertThatThrownBy(() -> add.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseUnfollow.countDown();
            unfollow.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> add.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotYourFollowerException.class);
        } finally {
            releaseUnfollow.countDown();
            threads.shutdownNow();
        }

        assertThat(memberIds(climbing, dani)).isEmpty();
        assertThat(added(alex)).isEmpty();
    }

    @Test
    void addingTwiceSendsOneCircleAdded() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long climbing = circleService.create("Climbing", dani).getId();

        circleService.addMember(climbing, idOf(alex), dani);
        circleService.addMember(climbing, idOf(alex), dani);

        assertThat(memberIds(climbing, dani)).containsExactly(idOf(alex));
        assertThat(added(alex)).singleElement().satisfies(n -> {
            assertThat(n.getActor().getId()).isEqualTo(idOf(dani));
            assertThat(n.getCircleId()).isEqualTo(climbing);
            assertThat(n.getCircleName()).isEqualTo("Climbing");
        });
    }

    @Test
    void unfollowingTheOwnerRemovesYouFromTheirCircles() {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        Long games = circleService.create("Board games", dani).getId();
        ClerkIdentity alex = member("alex", dani, climbing);
        circleService.addMember(games, idOf(alex), dani);

        followService.unfollow(idOf(dani), alex);

        assertThat(names(alex)).isEmpty();
        assertThat(memberIds(climbing, dani)).isEmpty();
        assertThat(memberIds(games, dani)).isEmpty();
    }

    @Test
    void followingAgainDoesNotPutYouBack() {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        ClerkIdentity alex = member("alex", dani, climbing);
        followService.unfollow(idOf(dani), alex);

        followService.follow(idOf(dani), alex);

        assertThat(names(alex)).isEmpty();
    }

    @Test
    void leavingOrAddingWhileTheCircleIsDeletedIsNotFound() throws Exception {
        ClerkIdentity dani = person("dani");
        Long climbing = circleService.create("Climbing", dani).getId();
        ClerkIdentity alex = member("alex", dani, climbing);
        ClerkIdentity sam = follower("sam", dani);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> delete = threads.submit(() -> transaction.executeWithoutResult(status -> {
                circleService.delete(climbing, dani);
                deleted.countDown();
                awaitOrFail(releaseDelete);
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> leave = threads.submit(() -> circleService.removeMember(climbing, idOf(alex), alex));
            Future<?> add = threads.submit(() -> circleService.addMember(climbing, idOf(sam), dani));

            // Both wait on the circle's row lock, held by the delete.
            assertThatThrownBy(() -> leave.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(add.isDone()).isFalse();

            releaseDelete.countDown();
            delete.get(10, TimeUnit.SECONDS);
            // Once the delete commits, they re-read the row, find it deleted, and get 404.
            assertThatThrownBy(() -> leave.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CircleNotFoundException.class);
            assertThatThrownBy(() -> add.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CircleNotFoundException.class);
        } finally {
            releaseDelete.countDown();
            threads.shutdownNow();
        }

        assertThat(added(sam)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from circle_members where circle_id = ?", Integer.class,
                climbing)).isEqualTo(1);
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
