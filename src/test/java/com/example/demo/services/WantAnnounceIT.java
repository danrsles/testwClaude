package com.example.demo.services;

import java.util.List;
import java.util.UUID;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.models.Category;
import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.repositories.WantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Announcing new wants to followers (WANT_POSTED) against a real PostgreSQL:
 * the native INSERT ... SELECT, the notifications_posted_once index that
 * stops anyone being told twice, and the transaction it shares with the
 * create or edit. Set up like WantSignupsIT: nothing is wrapped in a test
 * transaction, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantAnnounceIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private SignupService signupService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private WantRepository wantRepository;

    // A spy, so one test can make announcing fail; every other call is real.
    @MockitoSpyBean
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

    private Long want(ClerkIdentity owner, String message, Integer capacity) {
        return wantService.create(message, Category.FOOD, capacity, null, owner).id();
    }

    /** Edits only the message, leaving capacity, openness and deadline as they were. */
    private void rename(Long wantId, String message, ClerkIdentity owner) {
        wantService.update(wantId, message, Category.FOOD, null, null, null, null, owner);
    }

    private void raiseCapacity(Long wantId, String message, int capacity, ClerkIdentity owner) {
        wantService.update(wantId, message, Category.FOOD, capacity, null, null, null, owner);
    }

    private List<Notification> posted(ClerkIdentity recipient) {
        return notificationService.mine(recipient).stream()
                .filter(n -> n.getType() == NotificationType.WANT_POSTED)
                .toList();
    }

    @Test
    void followersAreToldAboutANewJoinableWant() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        ClerkIdentity sam = follower("sam", dani);

        Long pizza = want(dani, "Pizza Friday", 4);

        for (ClerkIdentity told : List.of(alex, sam)) {
            List<Notification> rows = posted(told);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getWantId()).isEqualTo(pizza);
            assertThat(rows.getFirst().getActor().getId()).isEqualTo(idOf(dani));
        }
    }

    @Test
    void theOwnerAndPeopleWhoDoNotFollowThemAreNotTold() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity stranger = person("stranger");
        // Dani follows kim, but kim doesn't follow dani: following runs one way.
        ClerkIdentity kim = person("kim");
        followService.follow(idOf(kim), dani);

        want(dani, "Pizza Friday", 4);

        assertThat(posted(dani)).isEmpty();
        assertThat(posted(stranger)).isEmpty();
        assertThat(posted(kim)).isEmpty();
    }

    @Test
    void nobodyIsToldTwiceWhenTheWantIsEditedAgain() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        rename(pizza, "Pizza Saturday", dani);
        rename(pizza, "Pizza Sunday", dani);

        assertThat(posted(alex)).hasSize(1);
    }

    @Test
    void raisingCapacityLaterAnnouncesItOnce() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 1);
        assertThat(posted(alex)).isEmpty();

        raiseCapacity(pizza, "Pizza Friday", 3, dani);
        assertThat(posted(alex)).hasSize(1);

        raiseCapacity(pizza, "Pizza Friday", 5, dani);
        assertThat(posted(alex)).hasSize(1);
    }

    @Test
    void peopleAlreadyInTheLineupAreNotTold() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        // Both follow after it was posted; sam finds it in the feed and signs up first.
        ClerkIdentity sam = follower("sam", dani);
        ClerkIdentity jordan = follower("jordan", dani);
        signupService.signUp(pizza, sam);

        rename(pizza, "Pizza Saturday", dani);

        assertThat(posted(sam)).isEmpty();
        assertThat(posted(jordan)).hasSize(1);
    }

    /** Every edit announces to whoever has not been told, so a new follower hears on the next one. */
    @Test
    void someoneWhoFollowsLaterIsToldOnTheNextEdit() {
        ClerkIdentity dani = person("dani");
        Long pizza = want(dani, "Pizza Friday", 4);
        ClerkIdentity jordan = follower("jordan", dani);
        assertThat(posted(jordan)).isEmpty();

        rename(pizza, "Pizza Saturday", dani);

        assertThat(posted(jordan)).hasSize(1);
    }

    /**
     * The announce statement repeats the feed's visibility rule in native
     * SQL. This fails if the two copies drift: everyone told must be able
     * to see the want.
     */
    @Test
    void everyoneToldCanSeeTheWant() {
        ClerkIdentity dani = person("dani");
        follower("alex", dani);
        follower("sam", dani);
        person("stranger");
        Long pizza = want(dani, "Pizza Friday", null);

        List<Long> told = jdbc.queryForList(
                "select recipient_id from notifications where want_id = ? and type = 'WANT_POSTED'", Long.class, pizza);

        assertThat(told).hasSize(2);
        assertThat(told).allSatisfy(recipient -> assertThat(wantRepository.isInFeed(pizza, recipient)).isTrue());
    }

    @Test
    void theNotificationKeepsTheMessageItWasPostedWith() {
        ClerkIdentity dani = person("dani");
        ClerkIdentity alex = follower("alex", dani);
        Long pizza = want(dani, "Pizza Friday", 4);

        rename(pizza, "Pizza Saturday", dani);

        assertThat(posted(alex)).singleElement()
                .extracting(Notification::getWantMessage).isEqualTo("Pizza Friday");
    }

    @Test
    void aFailedAnnounceRollsBackTheCreate() {
        ClerkIdentity dani = person("dani");
        follower("alex", dani);
        willThrow(new IllegalStateException("notifications are down"))
                .given(notificationService).announce(any(), any());

        assertThatThrownBy(() -> want(dani, "Pizza Friday", 4)).isInstanceOf(IllegalStateException.class);

        // One transaction: the want, its owner's seat and WANT_CREATED went with it.
        assertThat(jdbc.queryForObject("select count(*) from wants where user_id = ?", Integer.class, idOf(dani)))
                .isZero();
        assertThat(notificationService.mine(dani)).extracting(Notification::getType)
                .doesNotContain(NotificationType.WANT_CREATED);
    }
}
