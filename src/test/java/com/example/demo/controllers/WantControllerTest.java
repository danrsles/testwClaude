package com.example.demo.controllers;

import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.services.WantService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WantController.class)
class WantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WantService wantService;

    private final User dani = new User("dani");

    @Test
    void returnsAllWants() throws Exception {
        given(wantService.findAll()).willReturn(List.of(
                new Want("Ramen downtown", Category.FOOD, dani),
                new Want("Finish Elden Ring", Category.GAME, dani)));

        mockMvc.perform(get("/wants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("Ramen downtown"))
                .andExpect(jsonPath("$[0].category").value("FOOD"))
                .andExpect(jsonPath("$[0].user.username").value("dani"));
    }

    @Test
    void filtersByCategory() throws Exception {
        given(wantService.findByCategory(any(Category.class)))
                .willReturn(List.of(new Want("Weekend in Montreal", Category.TRIP, dani)));

        mockMvc.perform(get("/wants").param("category", "TRIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("TRIP"));
    }

    @Test
    void createsWant() throws Exception {
        given(wantService.create(eq("Finish Elden Ring"), eq(Category.GAME), eq(1L)))
                .willReturn(new Want("Finish Elden Ring", Category.GAME, dani));

        mockMvc.perform(post("/wants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Finish Elden Ring\",\"category\":\"GAME\",\"userId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Finish Elden Ring"))
                .andExpect(jsonPath("$.category").value("GAME"))
                .andExpect(jsonPath("$.user.username").value("dani"));
    }

    @Test
    void rejectsWantWithBlankMessage() throws Exception {
        mockMvc.perform(post("/wants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\",\"category\":\"GAME\",\"userId\":1}"))
                .andExpect(status().isBadRequest());
    }
}
