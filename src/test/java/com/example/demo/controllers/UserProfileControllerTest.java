package com.example.demo.controllers;

import java.util.List;

import com.example.demo.models.User;
import com.example.demo.models.UserProfile;
import com.example.demo.services.UserNotFoundException;
import com.example.demo.services.UserProfileConflictException;
import com.example.demo.services.UserProfileNotFoundException;
import com.example.demo.services.UserProfileService;

import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserProfileController.class)
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService profileService;

    private final User dani = new User("dani");

    private UserProfile daniProfile(String s3Url) {
        return new UserProfile(dani, "dani@example.com", "Dani", s3Url);
    }

    @Test
    void returnsAllProfiles() throws Exception {
        given(profileService.findAll()).willReturn(List.of(daniProfile("https://example.com/dani.png")));

        mockMvc.perform(get("/user/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("dani@example.com"))
                .andExpect(jsonPath("$[0].nickname").value("Dani"))
                .andExpect(jsonPath("$[0].s3Url").value("https://example.com/dani.png"))
                .andExpect(jsonPath("$[0].user.username").value("dani"));
    }

    @Test
    void filtersByUser() throws Exception {
        given(profileService.findByUserId(1L)).willReturn(List.of(daniProfile(null)));

        mockMvc.perform(get("/user/profiles").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nickname").value("Dani"));
    }

    @Test
    void returnsOneProfile() throws Exception {
        given(profileService.findById(1L)).willReturn(daniProfile(null));

        mockMvc.perform(get("/user/profiles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dani@example.com"));
    }

    @Test
    void unknownProfileIsNotFound() throws Exception {
        given(profileService.findById(99L)).willThrow(new UserProfileNotFoundException(99L));

        mockMvc.perform(get("/user/profiles/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsProfileWithoutAvatar() throws Exception {
        given(profileService.create(eq(1L), eq("dani@example.com"), eq("Dani"), isNull()))
                .willReturn(daniProfile(null));

        mockMvc.perform(post("/user/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"email\":\"dani@example.com\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("Dani"))
                .andExpect(jsonPath("$.s3Url").isEmpty());
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/user/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"email\":\"not-an-email\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankNickname() throws Exception {
        mockMvc.perform(post("/user/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"email\":\"dani@example.com\",\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingForUnknownUserIsNotFound() throws Exception {
        given(profileService.create(eq(99L), eq("ghost@example.com"), eq("Ghost"), isNull()))
                .willThrow(new UserNotFoundException(99L));

        mockMvc.perform(post("/user/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"email\":\"ghost@example.com\",\"nickname\":\"Ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateProfileIsConflict() throws Exception {
        given(profileService.create(eq(1L), eq("dani@example.com"), eq("Dani"), isNull()))
                .willThrow(new UserProfileConflictException("User 1 already has a profile"));

        mockMvc.perform(post("/user/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"email\":\"dani@example.com\",\"nickname\":\"Dani\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updatesProfile() throws Exception {
        given(profileService.update(eq(1L), eq("dani@example.org"), eq("Dan"), eq("https://example.com/d.png")))
                .willReturn(new UserProfile(dani, "dani@example.org", "Dan", "https://example.com/d.png"));

        mockMvc.perform(put("/user/profiles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dani@example.org\",\"nickname\":\"Dan\",\"s3Url\":\"https://example.com/d.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Dan"))
                .andExpect(jsonPath("$.s3Url").value("https://example.com/d.png"));
    }

    @Test
    void deletesProfile() throws Exception {
        mockMvc.perform(delete("/user/profiles/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingUnknownProfileIsNotFound() throws Exception {
        BDDMockito.willThrow(new UserProfileNotFoundException(99L)).given(profileService).delete(99L);

        mockMvc.perform(delete("/user/profiles/99"))
                .andExpect(status().isNotFound());
    }
}
