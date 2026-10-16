package com.example.demo.controllers;

import com.example.demo.models.User;
import com.example.demo.models.UserProfile;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.UserProfileConflictException;
import com.example.demo.services.UserProfileNotFoundException;
import com.example.demo.services.UserProfileService;

import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserProfileController.class)
@Import(SecurityConfig.class)
class UserProfileControllerTest {

    private static final String DANI_ID = "user_dani";
    // What the controller should hand the service for signedInAsDani(): the
    // token's subject plus its custom username claim.
    private static final ClerkIdentity DANI = new ClerkIdentity(DANI_ID, "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService profileService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User dani = new User("dani", DANI_ID);

    private UserProfile daniProfile(String s3Url) {
        return new UserProfile(dani, "dani@example.com", "Dani", s3Url);
    }

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject(DANI_ID).claim("username", "dani"));
    }

    @Test
    void returnsMyProfile() throws Exception {
        given(profileService.findMine(DANI)).willReturn(daniProfile("https://example.com/dani.png"));

        mockMvc.perform(get("/user/profiles/me").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dani@example.com"))
                .andExpect(jsonPath("$.nickname").value("Dani"))
                .andExpect(jsonPath("$.s3Url").value("https://example.com/dani.png"))
                .andExpect(jsonPath("$.user.username").value("dani"));
    }

    @Test
    void missingProfileIsNotFound() throws Exception {
        given(profileService.findMine(DANI)).willThrow(new UserProfileNotFoundException());

        mockMvc.perform(get("/user/profiles/me").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/user/profiles/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thereIsNoListOfEveryonesProfiles() throws Exception {
        // Profiles hold email addresses; the list endpoint was removed with logins.
        mockMvc.perform(get("/user/profiles").with(signedInAsDani()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void createsProfileWithoutAvatar() throws Exception {
        given(profileService.create(eq(DANI), eq("dani@example.com"), eq("Dani"), isNull()))
                .willReturn(daniProfile(null));

        mockMvc.perform(post("/user/profiles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dani@example.com\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("Dani"))
                .andExpect(jsonPath("$.s3Url").isEmpty());
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/user/profiles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankNickname() throws Exception {
        mockMvc.perform(post("/user/profiles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dani@example.com\",\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateProfileIsConflict() throws Exception {
        given(profileService.create(eq(DANI), eq("dani@example.com"), eq("Dani"), isNull()))
                .willThrow(new UserProfileConflictException("You already have a profile"));

        mockMvc.perform(post("/user/profiles").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dani@example.com\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updatesMyProfile() throws Exception {
        given(profileService.update(eq(DANI), eq("dani@example.org"), eq("Dan"), eq("https://example.com/d.png")))
                .willReturn(new UserProfile(dani, "dani@example.org", "Dan", "https://example.com/d.png"));

        mockMvc.perform(put("/user/profiles/me").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dani@example.org\",\"nickname\":\"Dan\",\"s3Url\":\"https://example.com/d.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Dan"))
                .andExpect(jsonPath("$.s3Url").value("https://example.com/d.png"));
    }

    @Test
    void deletesMyProfile() throws Exception {
        mockMvc.perform(delete("/user/profiles/me").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(profileService).should().delete(DANI);
    }

    @Test
    void deletingAMissingProfileIsNotFound() throws Exception {
        BDDMockito.willThrow(new UserProfileNotFoundException()).given(profileService).delete(DANI);

        mockMvc.perform(delete("/user/profiles/me").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }
}
