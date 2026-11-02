package com.example.demo.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.dto.WantResponse;
import com.example.demo.models.Category;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed in pages, newest first, by keyset: each page asks for wants older
 * than the last one it has. Set up like the other *IT classes: no test
 * transactions, and each test signs up its own users.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WantFeedPagingIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    private ClerkIdentity person(String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 8);
        ClerkIdentity who = new ClerkIdentity("user_" + unique, unique);
        userService.current(who);
        return who;
    }

    private long post(String message, Category category, ClerkIdentity who) {
        return wantService.create(message, category, 1, null, who).id();
    }

    private static List<String> messages(List<WantResponse> page) {
        return page.stream().map(WantResponse::message).toList();
    }

    private static Long lastId(List<WantResponse> page) {
        return page.get(page.size() - 1).id();
    }

    @Test
    void pagesThroughTheFeedNewestFirstWithoutGapsOrRepeats() {
        ClerkIdentity me = person("me");
        ClerkIdentity friend = person("friend");
        followService.follow(userService.current(friend).getId(), me);
        post("One", Category.FOOD, me);
        post("Two", Category.FOOD, friend);
        post("Three", Category.GAME, me);
        post("Four", Category.FOOD, friend);
        post("Five", Category.TRIP, me);

        List<WantResponse> first = wantService.findPage(me, null, null, 2);
        List<WantResponse> second = wantService.findPage(me, null, lastId(first), 2);
        List<WantResponse> third = wantService.findPage(me, null, lastId(second), 2);

        assertThat(messages(first)).containsExactly("Five", "Four");
        assertThat(messages(second)).containsExactly("Three", "Two");
        // Shorter than the limit: the last page.
        assertThat(messages(third)).containsExactly("One");
        assertThat(wantService.findPage(me, null, lastId(third), 2)).isEmpty();
    }

    @Test
    void aWantPostedBetweenPagesDoesNotShiftTheNextPage() {
        // An offset would: the new want pushes "Two" onto the second page,
        // which then repeats it. The cursor carries on from "Two" itself.
        ClerkIdentity me = person("me");
        post("One", Category.FOOD, me);
        post("Two", Category.FOOD, me);
        post("Three", Category.FOOD, me);

        List<WantResponse> first = wantService.findPage(me, null, null, 2);
        post("Four", Category.FOOD, me);
        List<WantResponse> second = wantService.findPage(me, null, lastId(first), 2);

        assertThat(messages(first)).containsExactly("Three", "Two");
        assertThat(messages(second)).containsExactly("One");
        assertThat(messages(wantService.findPage(me, null, null, 2))).containsExactly("Four", "Three");
    }

    @Test
    void pagesThroughOneCategory() {
        ClerkIdentity me = person("me");
        post("Ramen", Category.FOOD, me);
        post("Chess", Category.GAME, me);
        post("Tacos", Category.FOOD, me);
        post("Go", Category.GAME, me);
        post("Pho", Category.FOOD, me);

        List<String> food = new ArrayList<>();
        Long before = null;
        List<WantResponse> page;
        do {
            page = wantService.findPage(me, Category.FOOD, before, 2);
            food.addAll(messages(page));
            before = page.isEmpty() ? before : lastId(page);
        } while (page.size() == 2);

        assertThat(food).containsExactly("Pho", "Tacos", "Ramen");
    }
}
