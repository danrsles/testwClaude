package com.example.demo.controllers;

import java.util.List;

import com.example.demo.dto.PersonResult;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.FollowService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Set up like FollowControllerTest: the real filter chain, a mocked decoder.
@WebMvcTest(PeopleController.class)
@Import(SecurityConfig.class)
class PeopleControllerTest {

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

    @Test
    void searchesAsTheSignedInUserByPublicDetailsOnly() throws Exception {
        given(followService.search("al", DANI)).willReturn(List.of(
                new PersonResult(2L, "alex", "https://img.clerk.com/alex.jpg", true),
                new PersonResult(5L, "sal", null, false)));

        mockMvc.perform(get("/users").param("q", "al").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].username").value("alex"))
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.clerk.com/alex.jpg"))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[1].following").value(false))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].clerkId").doesNotExist())
                .andExpect(jsonPath("$[0].lastSignInAt").doesNotExist());
    }

    @Test
    void aMissingQueryIsAnEmptySearch() throws Exception {
        given(followService.search("", DANI)).willReturn(List.of());

        mockMvc.perform(get("/users").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchingNeedsAToken() throws Exception {
        mockMvc.perform(get("/users").param("q", "al")).andExpect(status().isUnauthorized());

        then(followService).should(never()).search(any(), any());
    }
}
