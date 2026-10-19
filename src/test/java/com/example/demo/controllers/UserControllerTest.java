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
    void returnsYourOwnPrivateDetails() throws Exception {
        User dani = new User("dani2", "user_dani");
        dani.setImageUrl("https://img.clerk.com/dani.jpg");
        dani.setEmail("dani@example.com");
        given(userService.current(new ClerkIdentity("user_dani", null))).willReturn(dani);

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_dani"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://img.clerk.com/dani.jpg"))
                // Your own email, shown only to you.
                .andExpect(jsonPath("$.email").value("dani@example.com"));
    }

    @Test
    void readsThePictureClaim() throws Exception {
        given(userService.current(new ClerkIdentity("user_dani", null, null, null,
                "https://img.clerk.com/dani.jpg")))
                .willReturn(new User("dani2", "user_dani"));

        mockMvc.perform(get("/me").with(jwt().jwt(token -> token.subject("user_dani")
                        .claim("imageUrl", "https://img.clerk.com/dani.jpg"))))
                .andExpect(status().isOk())
                // Only there if the stub matched, i.e. the claim reached ClerkIdentity.
                .andExpect(jsonPath("$.username").value("dani2"));
    }

    @Test
    void thereIsNoWayToEditYourself() throws Exception {
        // Everything about a user comes from Clerk, so /me is read-only.
        mockMvc.perform(put("/me").with(jwt().jwt(token -> token.subject("user_dani")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"evil@example.com\"}"))
                .andExpect(status().isMethodNotAllowed());
    }
}
