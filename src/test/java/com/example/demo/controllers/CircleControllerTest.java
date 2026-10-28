package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.Circle;
import com.example.demo.models.User;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.CircleForbiddenException;
import com.example.demo.services.CircleNotFoundException;
import com.example.demo.services.CircleService;
import com.example.demo.services.ClerkIdentity;

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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CircleController.class)
@Import(SecurityConfig.class)
class CircleControllerTest {

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CircleService circleService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject("user_dani").claim("username", "dani"));
    }

    private static User withPrivateDetails(String name, Long id) {
        User user = new User(name, "user_" + name);
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(name + "@example.com");
        user.setImageUrl("https://img.clerk.com/" + name + ".jpg");
        user.setLastSignInAt(Instant.parse("2026-10-16T12:00:00Z"));
        return user;
    }

    private static Circle circle(Long id, String name, User owner) {
        Circle circle = new Circle(owner, name);
        ReflectionTestUtils.setField(circle, "id", id);
        return circle;
    }

    private static String body(String name) {
        return "{\"name\": \"" + name + "\"}";
    }

    @Test
    void listsCirclesYouOwnOrAreInByPublicDetailsOnly() throws Exception {
        User alex = withPrivateDetails("alex", 2L);
        given(circleService.mine(DANI)).willReturn(List.of(circle(7L, "Climbing", alex)));

        mockMvc.perform(get("/circles").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Climbing"))
                .andExpect(jsonPath("$[0].owner.id").value(2))
                .andExpect(jsonPath("$[0].owner.username").value("alex"))
                .andExpect(jsonPath("$[0].owner.imageUrl").value("https://img.clerk.com/alex.jpg"))
                .andExpect(jsonPath("$[0].owner.email").doesNotExist())
                .andExpect(jsonPath("$[0].owner.clerkId").doesNotExist())
                .andExpect(content().string(not(containsString("alex@example.com"))));
    }

    @Test
    void createsACircle() throws Exception {
        given(circleService.create("Climbing", DANI))
                .willReturn(circle(7L, "Climbing", withPrivateDetails("dani", 1L)));

        mockMvc.perform(post("/circles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Climbing")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Climbing"));
    }

    @Test
    void aBlankOrLongNameIsABadRequest() throws Exception {
        mockMvc.perform(post("/circles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content(body("   ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/circles/7").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(41))))
                .andExpect(status().isBadRequest());

        then(circleService).should(never()).create(any(), any());
        then(circleService).should(never()).rename(any(), any(), any());
    }

    @Test
    void renamesACircle() throws Exception {
        given(circleService.rename(7L, "Bouldering", DANI))
                .willReturn(circle(7L, "Bouldering", withPrivateDetails("dani", 1L)));

        mockMvc.perform(put("/circles/7").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Bouldering")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bouldering"));
    }

    @Test
    void deletesACircle() throws Exception {
        mockMvc.perform(delete("/circles/7").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(circleService).should().delete(7L, DANI);
    }

    @Test
    void listsMembersByPublicDetailsOnly() throws Exception {
        given(circleService.members(7L, DANI)).willReturn(List.of(withPrivateDetails("sam", 3L)));

        mockMvc.perform(get("/circles/7/members").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].username").value("sam"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(content().string(not(containsString("sam@example.com"))));
    }

    @Test
    void addsAndRemovesAMember() throws Exception {
        mockMvc.perform(put("/circles/7/members/3").with(signedInAsDani()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/circles/7/members/3").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(circleService).should().addMember(7L, 3L, DANI);
        then(circleService).should().removeMember(7L, 3L, DANI);
    }

    @Test
    void leavesACircle() throws Exception {
        // Leaving is removing yourself; the service tells the two apart.
        mockMvc.perform(delete("/circles/7/members/1").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(circleService).should().removeMember(7L, 1L, DANI);
    }

    @Test
    void aMemberAskingForAnOwnerActionIs403() throws Exception {
        willThrow(new CircleForbiddenException(7L)).given(circleService).delete(7L, DANI);

        mockMvc.perform(delete("/circles/7").with(signedInAsDani()))
                .andExpect(status().isForbidden());
    }

    @Test
    void someoneElsesCircleIs404() throws Exception {
        given(circleService.members(8L, DANI)).willThrow(new CircleNotFoundException(8L));

        mockMvc.perform(get("/circles/8/members").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        mockMvc.perform(get("/circles")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/circles").contentType(MediaType.APPLICATION_JSON).content(body("Climbing")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/circles/7").contentType(MediaType.APPLICATION_JSON).content(body("Climbing")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/circles/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/circles/7/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/circles/7/members/3")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/circles/7/members/3")).andExpect(status().isUnauthorized());

        then(circleService).shouldHaveNoInteractions();
    }
}
