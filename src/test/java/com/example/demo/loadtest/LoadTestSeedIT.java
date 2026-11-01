package com.example.demo.loadtest;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.UserService;
import com.example.demo.services.WantService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * loadtest/seed.sql against a freshly migrated database, so a migration that
 * breaks it fails mvn verify rather than the next load test, months later.
 *
 * Set up like the other *IT classes: no test transactions, one shared
 * database. The seeded users never follow anyone the other tests create, so
 * their feeds are unchanged, and the seed has no deadlines, so expireDue
 * never touches it.
 *
 * Reseeding takes seconds, mostly in foreign key checks on columns with no
 * index (wants.user_id, want_signups.user_id), so tests that only read share
 * one seed (seedOnce) and only the two about reseeding run it again.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LoadTestSeedIT {

    private static final String LOAD_USERS = "SELECT id FROM users WHERE clerk_id ~ '^load_user_[0-9]+$'";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WantService wantService;

    @Autowired
    private UserService userService;

    private static boolean seeded;

    private void seed() {
        new ResourceDatabasePopulator(new FileSystemResource("loadtest/seed.sql")).execute(dataSource);
        seeded = true;
    }

    private void seedOnce() {
        if (!seeded) {
            seed();
        }
    }

    /** load_<n> as their token names them, so the service sees the same identity k6 does. */
    private static ClerkIdentity loadUser(int n) {
        return new ClerkIdentity("load_user_" + n, "load_" + n);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long seededUsers() {
        return count("SELECT count(*) FROM (" + LOAD_USERS + ") u");
    }

    private long seededWants() {
        return count("SELECT count(*) FROM wants WHERE user_id IN (" + LOAD_USERS + ")");
    }

    private long seededFollows() {
        return count("SELECT count(*) FROM follows WHERE follower_id IN (" + LOAD_USERS + ")");
    }

    private long seededCircles() {
        return count("SELECT count(*) FROM circles WHERE owner_id IN (" + LOAD_USERS + ")");
    }

    private long seededMembers() {
        return count("SELECT count(*) FROM circle_members WHERE owner_id IN (" + LOAD_USERS + ")");
    }

    private long seededSeats() {
        return count("SELECT count(*) FROM want_signups WHERE user_id IN (" + LOAD_USERS + ")");
    }

    @Test
    void seedRunsOnAFreshlyMigratedDatabase() {
        seedOnce();

        assertThat(seededUsers()).isEqualTo(1000);
        assertThat(seededWants()).isEqualTo(10_000);
        // 999 follow load_1, and everyone follows 50 more, 50 of which are
        // load_1 again and count once.
        assertThat(seededFollows()).isEqualTo(50_949);
        assertThat(seededCircles()).isEqualTo(100);
        assertThat(seededMembers()).isEqualTo(1000);
        assertThat(seededSeats()).isGreaterThan(10_000);
    }

    @Test
    void seedCanRunAgainWithoutDuplicating() {
        seedOnce();
        List<Long> first = List.of(seededUsers(), seededWants(), seededFollows(), seededCircles(), seededMembers(),
                seededSeats());

        seed();

        assertThat(List.of(seededUsers(), seededWants(), seededFollows(), seededCircles(), seededMembers(),
                seededSeats())).isEqualTo(first);
    }

    @Test
    void seedLeavesEveryoneElseAlone() {
        String unique = "outsider_" + UUID.randomUUID().toString().substring(0, 8);
        ClerkIdentity outsider = new ClerkIdentity("user_" + unique, unique);
        WantResponse theirs = wantService.create("Not load data", Category.FOOD, 1, null, outsider);
        long otherUsers = count("SELECT count(*) FROM users WHERE id NOT IN (" + LOAD_USERS + ")");
        long otherWants = count("SELECT count(*) FROM wants WHERE user_id NOT IN (" + LOAD_USERS + ")");

        seed();

        assertThat(count("SELECT count(*) FROM users WHERE id NOT IN (" + LOAD_USERS + ")")).isEqualTo(otherUsers);
        assertThat(count("SELECT count(*) FROM wants WHERE user_id NOT IN (" + LOAD_USERS + ")"))
                .isEqualTo(otherWants);
        assertThat(wantService.findAll(outsider)).extracting(WantResponse::id).contains(theirs.id());
    }

    @Test
    void everySeededUserHasAFeedToLoad() {
        seedOnce();

        for (int n : new int[] {1, 500, 1000}) {
            Long me = userService.current(loadUser(n)).getId();

            List<WantResponse> feed = wantService.findAll(loadUser(n));

            // Their own 10, and other people's from those they follow.
            assertThat(feed).filteredOn(w -> w.userId().equals(me)).hasSize(10);
            assertThat(feed).filteredOn(w -> !w.userId().equals(me)).isNotEmpty();
        }
    }

    @Test
    void everyRacerCanSeeTheRaceHostsWants() {
        seedOnce();
        Long host = userService.current(loadUser(1)).getId();

        WantResponse race = wantService.create("Race", Category.GAME, 6, null, loadUser(1));

        assertThat(count("SELECT count(*) FROM follows WHERE followee_id = " + host)).isEqualTo(999);
        for (int n : new int[] {2, 201, 1000}) {
            assertThat(wantService.findAll(loadUser(n))).extracting(WantResponse::id).contains(race.id());
        }
    }
}
