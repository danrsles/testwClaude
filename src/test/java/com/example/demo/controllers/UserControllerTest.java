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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
