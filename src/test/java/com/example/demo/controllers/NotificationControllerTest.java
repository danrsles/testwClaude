package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.Notification;
import com.example.demo.models.NotificationType;
import com.example.demo.models.User;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.NotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Set up like WantControllerTest: the real filter chain, a mocked decoder.
@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject("user_dani").claim("username", "dani"));
    }

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static User alexWithPrivateDetails() {
        User user = withId(new User("alex", "user_alex"), 2L);
        user.setEmail("alex@example.com");
        user.setImageUrl("https://img.clerk.com/alex.jpg");
        user.setLastSignInAt(Instant.parse("2026-10-16T12:00:00Z"));
        return user;
    }

    private static Notification alexFollowedDani(Long id, Instant readAt) {
        Notification notification = new Notification(withId(new User("dani", "user_dani"), 1L),
                alexWithPrivateDetails(), NotificationType.FOLLOW);
        ReflectionTestUtils.setField(notification, "id", id);
        ReflectionTestUtils.setField(notification, "createdAt", Instant.parse("2026-10-20T09:30:00Z"));
        ReflectionTestUtils.setField(notification, "readAt", readAt);
        return notification;
    }

    @Test
    void listsYourNotificationsWithTheActorByPublicDetailsOnly() throws Exception {
        given(notificationService.mine(DANI)).willReturn(List.of(
                alexFollowedDani(8L, null),
                alexFollowedDani(3L, Instant.parse("2026-10-20T10:00:00Z"))));

        mockMvc.perform(get("/notifications").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(8))
                .andExpect(jsonPath("$[0].type").value("FOLLOW"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-20T09:30:00Z"))
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[1].read").value(true))
                .andExpect(jsonPath("$[0].actor.id").value(2))
                .andExpect(jsonPath("$[0].actor.username").value("alex"))
                .andExpect(jsonPath("$[0].actor.imageUrl").value("https://img.clerk.com/alex.jpg"))
                .andExpect(jsonPath("$[0].actor.email").doesNotExist())
                .andExpect(jsonPath("$[0].actor.clerkId").doesNotExist())
                .andExpect(jsonPath("$[0].recipient").doesNotExist())
                .andExpect(content().string(not(containsString("alex@example.com"))));
    }

    @Test
    void marksReadUpToTheNewestShown() throws Exception {
        mockMvc.perform(post("/notifications/read").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upTo\": 8}"))
                .andExpect(status().isNoContent());

        then(notificationService).should().markRead(8L, DANI);
    }

    @Test
    void markingReadNeedsAnUpperBound() throws Exception {
        mockMvc.perform(post("/notifications/read").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        then(notificationService).should(never()).markRead(any(), any());
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        mockMvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upTo\": 8}"))
                .andExpect(status().isUnauthorized());

        then(notificationService).should(never()).mine(any());
        then(notificationService).should(never()).markRead(any(), any());
    }
}
