package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.Follow;
import com.example.demo.models.User;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.CannotFollowYourselfException;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.FollowService;
import com.example.demo.services.NotFollowingException;
import com.example.demo.services.UserNotFoundException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Set up like WantControllerTest: the real filter chain, a mocked decoder.
@WebMvcTest(FollowController.class)
@Import(SecurityConfig.class)
class FollowControllerTest {

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FollowService followService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject("user_dani").claim("username", "dani"));
    }

    private static User dani() {
        User user = new User("dani", "user_dani");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    private static User alexWithPrivateDetails() {
        User user = new User("alex", "user_alex");
        ReflectionTestUtils.setField(user, "id", 2L);
        user.setEmail("alex@example.com");
        user.setImageUrl("https://img.clerk.com/alex.jpg");
        user.setLastSignInAt(Instant.parse("2026-10-16T12:00:00Z"));
        return user;
    }

    @Test
    void followsAsTheSignedInUser() throws Exception {
        mockMvc.perform(put("/follows/2").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(followService).should().follow(2L, DANI);
    }

    @Test
    void unfollowsAsTheSignedInUser() throws Exception {
        mockMvc.perform(delete("/follows/2").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(followService).should().unfollow(2L, DANI);
    }

    @Test
    void followingYourselfIsABadRequest() throws Exception {
        willThrow(new CannotFollowYourselfException()).given(followService).follow(1L, DANI);

        mockMvc.perform(put("/follows/1").with(signedInAsDani()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void followingAnUnknownUserIsNotFound() throws Exception {
        willThrow(new UserNotFoundException(99L)).given(followService).follow(99L, DANI);

        mockMvc.perform(put("/follows/99").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsWhoYouFollowByPublicDetailsOnly() throws Exception {
        given(followService.following(DANI)).willReturn(List.of(new Follow(dani(), alexWithPrivateDetails())));

        mockMvc.perform(get("/follows").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].username").value("alex"))
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.clerk.com/alex.jpg"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].clerkId").doesNotExist())
                .andExpect(jsonPath("$[0].lastSignInAt").doesNotExist())
                .andExpect(content().string(not(containsString("alex@example.com"))));
    }

    @Test
    void listsWhoYouFollowWithWhetherTheyAreMuted() throws Exception {
        Follow muted = new Follow(dani(), alexWithPrivateDetails());
        ReflectionTestUtils.setField(muted, "notifyNewWants", false);
        given(followService.following(DANI)).willReturn(List.of(muted));

        mockMvc.perform(get("/follows").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].notifyNewWants").value(false));
    }

    @Test
    void mutesWithDelete() throws Exception {
        mockMvc.perform(delete("/follows/2/notify").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(followService).should().mute(2L, DANI);
    }

    @Test
    void unmutesWithPut() throws Exception {
        mockMvc.perform(put("/follows/2/notify").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(followService).should().unmute(2L, DANI);
    }

    @Test
    void mutingSomeoneYouDoNotFollowIs404() throws Exception {
        willThrow(new NotFollowingException(3L)).given(followService).mute(3L, DANI);
        willThrow(new NotFollowingException(3L)).given(followService).unmute(3L, DANI);

        mockMvc.perform(delete("/follows/3/notify").with(signedInAsDani()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/follows/3/notify").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void muteRoutesNeedAToken() throws Exception {
        mockMvc.perform(put("/follows/2/notify")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/follows/2/notify")).andExpect(status().isUnauthorized());

        then(followService).should(never()).mute(any(), any());
        then(followService).should(never()).unmute(any(), any());
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        mockMvc.perform(get("/follows")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/follows/2")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/follows/2")).andExpect(status().isUnauthorized());

        then(followService).should(never()).following(any());
        then(followService).should(never()).follow(any(), any());
        then(followService).should(never()).unfollow(any(), any());
    }
}
