package com.example.demo.controllers;

import java.time.Instant;
import java.util.List;

import com.example.demo.dto.WantResponse;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    /** A want as the service returns it: just its owner seated, and dani is that owner. */
    private static WantResponse seated(Want want) {
        return WantResponse.from(want, 1, true);
    }

    private static RequestPostProcessor signedInAsDani() {
        return jwt().jwt(token -> token.subject(DANI_ID).claim("username", "dani"));
    }

    @Test
    void returnsAllWants() throws Exception {
        given(wantService.findAll(DANI)).willReturn(List.of(
                seated(new Want("Ramen downtown", Category.FOOD, dani)),
                seated(new Want("Finish Elden Ring", Category.GAME, dani))));

        mockMvc.perform(get("/wants").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("Ramen downtown"))
                .andExpect(jsonPath("$[0].category").value("FOOD"))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].username").value("dani"))
                // The owner's Clerk picture, public by design, for showing beside the want.
                .andExpect(jsonPath("$[0].imageUrl").value("https://img.clerk.com/dani.jpg"))
                .andExpect(jsonPath("$[0].capacity").value(1))
                .andExpect(jsonPath("$[0].seatsTaken").value(1))
                .andExpect(jsonPath("$[0].signedUp").value(true));
    }

    @Test
    void neverExposesTheOwnersPrivateDetails() throws Exception {
        // Your followers see your wants, so the owner must appear
        // only as userId, username and picture, never as the User entity.
        given(wantService.findAll(DANI)).willReturn(List.of(seated(new Want("Ramen downtown", Category.FOOD, dani))));

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
                .willReturn(List.of(seated(new Want("Weekend in Montreal", Category.TRIP, dani))));

        mockMvc.perform(get("/wants").param("category", "TRIP").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("TRIP"));
    }

    @Test
    void createsWantForTheSignedInUser() throws Exception {
        given(wantService.create(eq("Finish Elden Ring"), eq(Category.GAME), eq(1), eq(DANI)))
                .willReturn(seated(new Want("Finish Elden Ring", Category.GAME, dani)));

        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Finish Elden Ring\",\"category\":\"GAME\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Finish Elden Ring"))
                .andExpect(jsonPath("$.username").value("dani"));
    }

    @Test
    void ignoresAUserIdInTheBody() throws Exception {
        given(wantService.create(eq("Tacos"), eq(Category.FOOD), eq(1), eq(DANI)))
                .willReturn(seated(new Want("Tacos", Category.FOOD, dani)));

        // An old client, or a forged request, naming another user: the token wins.
        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\",\"userId\":2}"))
                .andExpect(status().isCreated());

        then(wantService).should().create("Tacos", Category.FOOD, 1, DANI);
    }

    @Test
    void creatingWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/wants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\"}"))
                .andExpect(status().isUnauthorized());

        then(wantService).should(never()).create(any(), any(), anyInt(), any());
    }

    @Test
    void creatingWithoutCapacityDefaultsToOne() throws Exception {
        // Clients from before signups send no capacity: the want takes just its owner.
        given(wantService.create(eq("Tacos"), eq(Category.FOOD), eq(1), eq(DANI)))
                .willReturn(seated(new Want("Tacos", Category.FOOD, dani)));

        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\"}"))
                .andExpect(status().isCreated());

        then(wantService).should().create("Tacos", Category.FOOD, 1, DANI);
    }

    @Test
    void createsWantWithACapacity() throws Exception {
        given(wantService.create(eq("Pizza Friday"), eq(Category.FOOD), eq(4), eq(DANI)))
                .willReturn(seated(new Want("Pizza Friday", Category.FOOD, dani, 4)));

        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Pizza Friday\",\"category\":\"FOOD\",\"capacity\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacity").value(4));
    }

    @Test
    void capacityMustBeAtLeastOne() throws Exception {
        // It counts the owner, so 0 would be a want its own owner is not in.
        mockMvc.perform(post("/wants").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Tacos\",\"category\":\"FOOD\",\"capacity\":0}"))
                .andExpect(status().isBadRequest());

        then(wantService).should(never()).create(any(), any(), anyInt(), any());
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
        given(wantService.update(eq(1L), eq("Ramen somewhere else"), eq(Category.FOOD), isNull(), eq(DANI)))
                .willReturn(seated(new Want("Ramen somewhere else", Category.FOOD, dani)));

        mockMvc.perform(put("/wants/1").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Ramen somewhere else\",\"category\":\"FOOD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ramen somewhere else"));
    }

    @Test
    void updatingSomeoneElsesWantIsForbidden() throws Exception {
        given(wantService.update(eq(2L), any(), any(), any(), eq(DANI))).willThrow(new WantForbiddenException(2L));

        mockMvc.perform(put("/wants/2").with(signedInAsDani())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Mine now\",\"category\":\"FOOD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingCancelsTheWant() throws Exception {
        mockMvc.perform(delete("/wants/1").with(signedInAsDani()))
                .andExpect(status().isNoContent());

        then(wantService).should().cancel(1L, DANI);
    }

    @Test
    void feedShowsEachWantsStatus() throws Exception {
        Want closed = new Want("Pizza Friday", Category.FOOD, dani, 4);
        closed.close(Instant.parse("2026-10-22T18:00:00Z"));
        given(wantService.findAll(DANI)).willReturn(List.of(
                seated(new Want("Ramen downtown", Category.FOOD, dani)), seated(closed)));

        mockMvc.perform(get("/wants").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("CLOSED"));
    }

    @Test
    void closeAndReopenReturnTheWantWithItsStatus() throws Exception {
        Want closed = new Want("Pizza Friday", Category.FOOD, dani, 4);
        closed.close(Instant.parse("2026-10-22T18:00:00Z"));
        given(wantService.close(1L, DANI)).willReturn(seated(closed));
        given(wantService.reopen(1L, DANI)).willReturn(seated(new Want("Pizza Friday", Category.FOOD, dani, 4)));

        mockMvc.perform(post("/wants/1/close").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Pizza Friday"))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/wants/1/reopen").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void closeReopenWithoutATokenAreUnauthorized() throws Exception {
        mockMvc.perform(post("/wants/1/close")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/wants/1/reopen")).andExpect(status().isUnauthorized());

        then(wantService).should(never()).close(any(), any());
        then(wantService).should(never()).reopen(any(), any());
    }

    @Test
    void startReturnsTheWantAsHappening() throws Exception {
        Want started = new Want("Pizza Friday", Category.FOOD, dani, 4);
        started.start(Instant.parse("2026-10-23T19:00:00Z"));
        given(wantService.start(1L, DANI)).willReturn(seated(started));

        mockMvc.perform(post("/wants/1/start").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Pizza Friday"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.happening").value(true));
    }

    @Test
    void endReturnsTheWantAsEnded() throws Exception {
        Want ended = new Want("Pizza Friday", Category.FOOD, dani, 4);
        ended.start(Instant.parse("2026-10-23T19:00:00Z"));
        ended.end(Instant.parse("2026-10-23T22:00:00Z"));
        given(wantService.end(1L, DANI)).willReturn(seated(ended));

        mockMvc.perform(post("/wants/1/end").with(signedInAsDani()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.happening").value(false));
    }

    @Test
    void startAndEndRequireAToken() throws Exception {
        mockMvc.perform(post("/wants/1/start")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/wants/1/end")).andExpect(status().isUnauthorized());

        then(wantService).should(never()).start(any(), any());
        then(wantService).should(never()).end(any(), any());
    }

    @Test
    void startingSomeoneElsesWantIsForbidden() throws Exception {
        given(wantService.start(2L, DANI)).willThrow(new WantForbiddenException(2L));

        mockMvc.perform(post("/wants/2/start").with(signedInAsDani()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingUnknownWantIsNotFound() throws Exception {
        BDDMockito.willThrow(new WantNotFoundException(99L)).given(wantService).cancel(99L, DANI);

        mockMvc.perform(delete("/wants/99").with(signedInAsDani()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingSomeoneElsesWantIsForbidden() throws Exception {
        BDDMockito.willThrow(new WantForbiddenException(2L)).given(wantService).cancel(2L, DANI);

        mockMvc.perform(delete("/wants/2").with(signedInAsDani()))
                .andExpect(status().isForbidden());
    }
}
