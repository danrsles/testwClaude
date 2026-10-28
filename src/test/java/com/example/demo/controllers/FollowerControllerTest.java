package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.User;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.FollowService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FollowerController.class)
@Import(SecurityConfig.class)
class FollowerControllerTest {

    private static final ClerkIdentity DANI = new ClerkIdentity("user_dani", "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FollowService followService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listsYourFollowersByPublicDetailsOnly() throws Exception {
        User sam = new User("sam", "user_sam");
        ReflectionTestUtils.setField(sam, "id", 3L);
        sam.setEmail("sam@example.com");
        sam.setLastSignInAt(Instant.parse("2026-10-16T12:00:00Z"));
        given(followService.followers(DANI)).willReturn(List.of(sam));

        mockMvc.perform(get("/followers").with(jwt().jwt(t -> t.subject("user_dani").claim("username", "dani"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].username").value("sam"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].clerkId").doesNotExist())
                .andExpect(jsonPath("$[0].lastSignInAt").doesNotExist())
                .andExpect(content().string(not(containsString("sam@example.com"))));
    }

    @Test
    void needsAToken() throws Exception {
        mockMvc.perform(get("/followers")).andExpect(status().isUnauthorized());

        then(followService).should(never()).followers(any());
    }
}
