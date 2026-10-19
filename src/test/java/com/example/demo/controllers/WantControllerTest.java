package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.models.Category;
import com.example.demo.models.User;
import com.example.demo.models.Want;
import com.example.demo.security.SecurityConfig;
import com.example.demo.services.ClerkIdentity;
import com.example.demo.services.WantForbiddenException;
import com.example.demo.services.WantNotFoundException;
import com.example.demo.services.WantService;

import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig is imported so these run behind the real filter chain. The
// JwtDecoder is mocked because jwt() below hands MockMvc an already-verified
// token; nothing ever contacts Clerk.
@WebMvcTest(WantController.class)
@Import(SecurityConfig.class)
class WantControllerTest {

    private static final String DANI_ID = "user_dani";
    // What the controller should hand the service for signedInAsDani(): the
    // token's subject plus its custom username claim.
    private static final ClerkIdentity DANI = new ClerkIdentity(DANI_ID, "dani");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WantService wantService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User dani = daniWithPrivateDetails();

    private static User daniWithPrivateDetails() {
        User user = new User("dani", DANI_ID);
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setEmail("dani@example.com");
        user.setImageUrl("https://img.clerk.com/dani.jpg");
        user.setLastSignInAt(Instant.parse("2026-10-16T12:00:00Z"));
        return user;
    }

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject(DANI_ID).claim("username", "dani"));
    }

    @Test
    void returnsAllWants() throws Exception {
        given(wantService.findAll(DANI)).willReturn(List.of(
                new Want("Ramen downtown", Category.FOOD, dani),
                new Want("Finish Elden Ring", Category.GAME, dani)));

        mockMvc.perform(get("/wants").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("Ramen downtown"))
                .andExpect(jsonPath("$[0].category").value("FOOD"))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].username").value("dani"))
                // The owner's Clerk picture, public by design, for showing beside the want.
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.clerk.com/dani.jpg"));
    }

    @Test
    void neverExposesTheOwnersPrivateDetails() throws Exception {
        // Every signed-in user can list every want, so the owner must appear
        // only as userId, username and picture, never as the User entity.
        given(wantService.findAll(DANI)).willReturn(List.of(new Want("Ramen downtown", Category.FOOD, dani)));

        mockMvc.perform(get("/wants").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].user").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].lastSignInAt").doesNotExist())
                .andExpect(jsonPath("$[0].clerkId").doesNotExist())
                .andExpect(content().string(not(containsString("dani@example.com"))));
    }

    @Test
    void readingWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/wants"))
                .andExpect(status().isUnauthorized());

        then(wantService).should(never()).findAll(any());
    }

    @Test
    void filtersByCategory() throws Exception {
        given(wantService.findByCategory(eq(Category.TRIP), eq(DANI)))
                .willReturn(List.of(new Want("Weekend in Montreal", Category.TRIP, dani)));

        mockMvc.perform(get("/wants").param("category", "TRIP").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("TRIP"));
    }

    @Test
    void createsWantForTheSignedInUser() throws Exception {
        given(wantService.create(eq("Finish Elden Ring"), eq(Category.GAME), eq(DANI)))
                .willReturn(new Want("Finish Elden Ring", Category.GAME, dani));

        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Finish Elden Ring\",\"category\":\"GAME\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Finish Elden Ring"))
                .andExpect(jsonPath("$.username").value("dani"));
    }

    @Test
    void ignoresAUserIdInTheBody() throws Exception {
        given(wantService.create(eq("Tacos"), eq(Category.FOOD), eq(DANI)))
                .willReturn(new Want("Tacos", Category.FOOD, dani));

        // An old client, or a forged request, naming another user: the token wins.
        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\",\"userId\":2}"))
                .andExpect(status().isCreated());

        then(wantService).should().create("Tacos", Category.FOOD, DANI);
    }

    @Test
    void creatingWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/wants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\"}"))
                .andExpect(status().isUnauthorized());

        then(wantService).should(never()).create(any(), any(), any());
    }

    @Test
    void rejectsWantWithBlankMessage() throws Exception {
        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\",\"category\":\"GAME\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesWant() throws Exception {
        given(wantService.update(eq(1L), eq("Ramen somewhere else"), eq(Category.FOOD), eq(DANI)))
                .willReturn(new Want("Ramen somewhere else", Category.FOOD, dani));

        mockMvc.perform(put("/wants/1").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Ramen somewhere else\",\"category\":\"FOOD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ramen somewhere else"));
    }

    @Test
    void updatingSomeoneElsesWantIsForbidden() throws Exception {
        given(wantService.update(eq(2L), any(), any(), eq(DANI))).willThrow(new WantForbiddenException(2L));

        mockMvc.perform(put("/wants/2").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Mine now\",\"category\":\"FOOD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletesWant() throws Exception {
        mockMvc.perform(delete("/wants/1").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(wantService).should().delete(1L, DANI);
    }

    @Test
    void deletingUnknownWantIsNotFound() throws Exception {
        BDDMockito.willThrow(new WantNotFoundException(99L)).given(wantService).delete(99L, DANI);

        mockMvc.perform(delete("/wants/99").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingSomeoneElsesWantIsForbidden() throws Exception {
        BDDMockito.willThrow(new WantForbiddenException(2L)).given(wantService).delete(2L, DANI);

        mockMvc.perform(delete("/wants/2").with(signedInAsDani()))
                .andExpect(status().isForbidden());
    }
}
