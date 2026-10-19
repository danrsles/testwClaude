package com.example.demo.controllers;

import java.time.Instant;

import com.example.demo.models.User;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void passesTheUsernameClaimToTheService() throws Exception {
        given(userService.current(new ClerkIdentity("user_new", "newbie")))
                .willReturn(new User("newbie", "user_new"));

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_new").claim("username", "newbie"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newbie"))
                .andExpect(jsonPath("$.clerkId").value("user_new"));
    }

    @Test
    void readsTheEmailAndLastLoginClaims() throws Exception {
        // Clerk renders {{user.last_sign_in_at}} as Unix seconds.
        given(userService.current(new ClerkIdentity("user_new", "newbie", "new@example.com",
                Instant.ofEpochSecond(1792177886))))
                .willReturn(new User("newbie", "user_new"));

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_new")
                        .claim("username", "newbie")
                        .claim("email", "new@example.com")
                        .claim("lastLogin", 1792177886L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newbie"));
    }

    @Test
    void aTokenWithoutTheClaimHasNoUsername() throws Exception {
        // A Clerk user with no username, or a session token not customized to carry one.
        given(userService.current(new ClerkIdentity("user_new", null)))
                .willReturn(new User("user_new", "user_new"));

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_new"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user_new"));
    }

    @Test
    void withoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheProfileFieldsWithTheAccount() throws Exception {
        User dani = new User("dani2", "user_dani");
        dani.setNickname("Dani");
        dani.setS3Url("https://example.com/dani.png");
        dani.setEmail("dani@example.com");
        given(userService.current(new ClerkIdentity("user_dani", null))).willReturn(dani);

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_dani"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Dani"))
                .andExpect(jsonPath("$.s3Url").value("https://example.com/dani.png"))
                // Your own email, shown only to you.
                .andExpect(jsonPath("$.email").value("dani@example.com"));
    }

    @Test
    void updatesMyNicknameAndAvatar() throws Exception {
        User updated = new User("dani2", "user_dani");
        updated.setNickname("Dan");
        given(userService.updateMe(new ClerkIdentity("user_dani", null), "Dan", "https://example.com/d.png"))
                .willReturn(updated);

        mockMvc.perform(put("/me").with(jwt().jwt(token -> token.subject("user_dani")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Dan\",\"s3Url\":\"https://example.com/d.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Dan"));
    }

    @Test
    void ignoresAttemptsToChangeTheEmailOrUsername() throws Exception {
        // Clerk owns those; the request record has no field to bind them to.
        given(userService.updateMe(new ClerkIdentity("user_dani", null), "Dan", null))
                .willReturn(new User("dani2", "user_dani"));

        mockMvc.perform(put("/me").with(jwt().jwt(token -> token.subject("user_dani")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Dan\",\"email\":\"evil@example.com\",\"username\":\"admin\"}"))
                .andExpect(status().isOk());

        then(userService).should().updateMe(new ClerkIdentity("user_dani", null), "Dan", null);
    }

    @Test
    void rejectsATooLongNickname() throws Exception {
        mockMvc.perform(put("/me").with(jwt().jwt(token -> token.subject("user_dani")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(put("/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Dan\"}"))
                .andExpect(status().isUnauthorized());
    }
}
