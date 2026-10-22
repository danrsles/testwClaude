package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.SignupEventDetails;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.SignupService;
import com.example.demo.services.WantFullException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Set up like WantControllerTest: the real filter chain, a mocked decoder.
@WebMvcTest(SignupController.class)
@Import(SecurityConfig.class)
class SignupControllerTest {

    private static final ClerkIdentity ALEX = new ClerkIdentity("user_alex", "alex");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SignupService signupService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor signedInAsAlex() {
        return jwt().jwt(token -> token.subject("user_alex").claim("username", "alex"));
    }

    @Test
    void signingUpReturnsTheEventDetails() throws Exception {
        given(signupService.signUp(7L, ALEX)).willReturn(
                new SignupEventDetails(7L, "Pizza Friday", 4, 2, List.of("dani", "alex"), true));

        mockMvc.perform(post("/wants/7/signups").with(signedInAsAlex()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wantId").value(7))
                .andExpect(jsonPath("$.message").value("Pizza Friday"))
                .andExpect(jsonPath("$.capacity").value(4))
                .andExpect(jsonPath("$.seatsTaken").value(2))
                .andExpect(jsonPath("$.signedUp").value(true));
    }

    @Test
    void signupDetailsShowTheLineupByUsernameOnly() throws Exception {
        given(signupService.signUp(7L, ALEX)).willReturn(
                new SignupEventDetails(7L, "Pizza Friday", 4, 2, List.of("dani", "alex"), true));

        // Plain names: no ids, emails or Clerk ids of the others in the lineup.
        mockMvc.perform(post("/wants/7/signups").with(signedInAsAlex()))
                .andExpect(jsonPath("$.lineup.length()").value(2))
                .andExpect(jsonPath("$.lineup[0]").value("dani"))
                .andExpect(jsonPath("$.lineup[1]").value("alex"))
                .andExpect(content().string(not(containsString("user_"))));
    }

    @Test
    void withdrawingReturnsTheEventDetails() throws Exception {
        given(signupService.withdraw(7L, ALEX)).willReturn(
                new SignupEventDetails(7L, "Pizza Friday", 4, 1, List.of("dani"), false));

        mockMvc.perform(delete("/wants/7/signups").with(signedInAsAlex()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatsTaken").value(1))
                .andExpect(jsonPath("$.signedUp").value(false));
    }

    @Test
    void aFullWantIsAConflict() throws Exception {
        given(signupService.signUp(7L, ALEX)).willThrow(new WantFullException(7L));

        mockMvc.perform(post("/wants/7/signups").with(signedInAsAlex()))
                .andExpect(status().isConflict());
    }

    @Test
    void signupRoutesWithoutATokenAreUnauthorized() throws Exception {
        mockMvc.perform(post("/wants/7/signups")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/wants/7/signups")).andExpect(status().isUnauthorized());

        then(signupService).should(never()).signUp(any(), any());
        then(signupService).should(never()).withdraw(any(), any());
    }
}
