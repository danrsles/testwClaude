package com.example.demo.services;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many statements one feed takes, counted by Hibernate's statistics
 * (on in tests only, src/test/resources/config/application.properties).
 *
 * Without the owners fetched in the feed query, Hibernate loads each want's
 * owner with a query of its own, so the count grows with the number of
 * people you follow (N+1). These follow 2 people, then 10 more, and expect
 * the same count both times. They don't pin a number, so a change elsewhere
 * in the request doesn't break them; only growth does.
 *
 * Set up like the other *IT classes: no test transactions, and each test
 * signs up its own users. Statistics are global, but the IT classes run one
 * at a time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantFeedQueriesIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ClerkIdentity person(String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 8);
        ClerkIdentity who = new ClerkIdentity("user_" + unique, unique);
        userService.current(who);
        return who;
    }

    /** Follow this many new people, each with a General want of their own. */
    private void followNewPeople(ClerkIdentity me, int howMany, Category category) {
        for (int i = 0; i < howMany; i++) {
            ClerkIdentity friend = person("friend");
            wantService.create("Want " + i, category, 1, null, friend);
            followService.follow(userService.current(friend).getId(), me);
        }
    }

    /** The statements one call runs, and checks the feed it returned holds the expected number of wants. */
    private long statementsFor(Supplier<List<WantResponse>> feed, int expectedWants) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<WantResponse> wants = feed.get();
        long count = statistics.getPrepareStatementCount();
        assertThat(wants).hasSize(expectedWants);
        return count;
    }

    @Test
    void feedTakesTheSameNumberOfQueriesHoweverManyPeopleYouFollow() {
        ClerkIdentity me = person("me");
        followNewPeople(me, 2, Category.FOOD);
        long withTwo = statementsFor(() -> wantService.findAll(me), 2);

        followNewPeople(me, 10, Category.FOOD);
        long withTwelve = statementsFor(() -> wantService.findAll(me), 12);

        assertThat(withTwelve).isEqualTo(withTwo);
    }

    @Test
    void categoryFeedTakesTheSameNumberOfQueriesHoweverManyPeopleYouFollow() {
        ClerkIdentity me = person("me");
        followNewPeople(me, 2, Category.MOVIE);
        long withTwo = statementsFor(() -> wantService.findByCategory(Category.MOVIE, me), 2);

        followNewPeople(me, 10, Category.MOVIE);
        long withTwelve = statementsFor(() -> wantService.findByCategory(Category.MOVIE, me), 12);

        assertThat(withTwelve).isEqualTo(withTwo);
    }
}
