package com.example.demo.services;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.repositories.FollowRepository;

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
import static org.mockito.BDDMockito.willThrow;

/**
 * Following and notifications against a real PostgreSQL: the SQL, the
 * constraints and the transactions that FollowServiceTest replaces with fakes.
 *
 * The whole application context starts, so Flyway runs every migration on an
 * empty database and Hibernate validates every entity against the result;
 * a migration that fails or drifts from its entity fails this class first.
 *
 * Nothing here is wrapped in a test transaction: each service call commits
 * for real, as in the app, which is what lets the rollback test see a
 * rollback. Each test signs up its own users with fresh Clerk ids instead of
 * cleaning tables, so tests cannot see each other's rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FollowNotificationsIT {

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    /** Real unless a test says otherwise; Spring resets it after each test. */
    @MockitoSpyBean
    private NotificationService notificationService;

    /** A new user, created as on their first signed-in request. */
    private ClerkIdentity signUp(String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 8);
        ClerkIdentity who = new ClerkIdentity("user_" + unique, unique);
        userService.current(who);
        return who;
    }

    private Long idOf(ClerkIdentity who) {
        return userService.current(who).getId();
    }

    private int rows(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void followIfAbsentReportsWhetherItInserted() {
        Long a = idOf(signUp("a"));
        Long b = idOf(signUp("b"));

        Integer first = transaction.execute(s -> followRepository.followIfAbsent(a, b));
        Integer again = transaction.execute(s -> followRepository.followIfAbsent(a, b));

        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(rows("select count(*) from follows where follower_id = ? and followee_id = ?", a, b))
                .isEqualTo(1);
    }

    @Test
    void aNewFollowNotifiesOnceEvenWhenRepeated() {
        ClerkIdentity alex = signUp("alex");
        ClerkIdentity dani = signUp("dani");

        followService.follow(idOf(dani), alex);
        followService.follow(idOf(dani), alex);

        List<Notification> danis = notificationService.mine(dani);
        assertThat(danis).hasSize(1);
        Notification followed = danis.getFirst();
        assertThat(followed.getType()).isEqualTo(NotificationType.FOLLOW);
        assertThat(followed.getActor().getId()).isEqualTo(idOf(alex));
        assertThat(followed.getCreatedAt()).isNotNull();
        assertThat(followed.getReadAt()).isNull();
        // The follower hears nothing about their own follow.
        assertThat(notificationService.mine(alex)).isEmpty();
    }

    @Test
    void followingAgainAfterUnfollowingNotifiesAgain() {
        ClerkIdentity alex = signUp("alex");
        ClerkIdentity dani = signUp("dani");

        followService.follow(idOf(dani), alex);
        followService.unfollow(idOf(dani), alex);
        followService.follow(idOf(dani), alex);

        assertThat(notificationService.mine(dani)).hasSize(2);
    }

    @Test
    void followingYourselfWritesNothing() {
        ClerkIdentity dani = signUp("dani");

        assertThatThrownBy(() -> followService.follow(idOf(dani), dani))
                .isInstanceOf(CannotFollowYourselfException.class);

        assertThat(rows("select count(*) from follows where follower_id = ?", idOf(dani))).isZero();
        assertThat(notificationService.mine(dani)).isEmpty();
    }

    @Test
    void theFollowIsUndoneWhenItsNotificationFails() {
        ClerkIdentity alex = signUp("alex");
        ClerkIdentity dani = signUp("dani");
        willThrow(new IllegalStateException("notifications are down"))
                .given(notificationService).notify(any(), any(), any());

        assertThatThrownBy(() -> followService.follow(idOf(dani), alex))
                .isInstanceOf(IllegalStateException.class);

        // One transaction: the follow inserted before the failure rolled back with it.
        assertThat(rows("select count(*) from follows where follower_id = ?", idOf(alex))).isZero();
    }

    @Test
    void listsNewestFirst() {
        ClerkIdentity dani = signUp("dani");
        ClerkIdentity alex = signUp("alex");
        ClerkIdentity sam = signUp("sam");

        followService.follow(idOf(dani), alex);
        followService.follow(idOf(dani), sam);

        assertThat(notificationService.mine(dani)).extracting(n -> n.getActor().getId())
                .containsExactly(idOf(sam), idOf(alex));
    }

    @Test
    void markReadStopsAtTheNewestShownAndTouchesOnlyYours() {
        ClerkIdentity dani = signUp("dani");
        ClerkIdentity jordan = signUp("jordan");
        ClerkIdentity alex = signUp("alex");
        ClerkIdentity sam = signUp("sam");
        followService.follow(idOf(dani), alex);
        followService.follow(idOf(jordan), alex);
        Long shown = notificationService.mine(dani).getFirst().getId();
        // Arrives after dani's page was rendered.
        followService.follow(idOf(dani), sam);

        notificationService.markRead(shown, dani);

        assertThat(notificationService.mine(dani))
                .extracting(n -> n.getActor().getId(), n -> n.getReadAt() != null)
                .containsExactly(
                        tuple(idOf(sam), false),
                        tuple(idOf(alex), true));
        // jordan's notification has a lower id than sam's, but is not dani's.
        assertThat(notificationService.mine(jordan)).allSatisfy(n -> assertThat(n.getReadAt()).isNull());
    }

    @Test
    void markingReadAgainKeepsTheFirstReadTime() throws InterruptedException {
        ClerkIdentity dani = signUp("dani");
        ClerkIdentity alex = signUp("alex");
        followService.follow(idOf(dani), alex);
        Long id = notificationService.mine(dani).getFirst().getId();

        notificationService.markRead(id, dani);
        Instant first = notificationService.mine(dani).getFirst().getReadAt();
        Thread.sleep(5);
        notificationService.markRead(id, dani);

        assertThat(notificationService.mine(dani).getFirst().getReadAt()).isEqualTo(first);
    }

    @Test
    void deletingAUserDeletesTheirNotifications() {
        ClerkIdentity dani = signUp("dani");
        ClerkIdentity alex = signUp("alex");
        followService.follow(idOf(dani), alex);
        User gone = userService.current(alex);

        jdbc.update("delete from users where id = ?", gone.getId());

        assertThat(notificationService.mine(dani)).isEmpty();
    }
}
