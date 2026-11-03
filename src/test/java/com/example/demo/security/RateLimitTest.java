package com.example.demo.security;

import com.example.demo.controllers.WantController;
import com.example.demo.services.WantService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The limiter behind the real filter chain, on a real route, with limits small
// enough to reach. The bucket arithmetic itself is tested in RateLimiterTest.
//
// Every test signs in as its own user: the context, and the buckets in it, are
// shared by all the tests in this class.
@WebMvcTest(WantController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.writes.capacity=2",
        "app.rate-limit.writes.per-minute=30",
        "app.rate-limit.reads.capacity=3",
        "app.rate-limit.reads.per-minute=60",
})
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WantService wantService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(t -> t.subject(userId));
    }

    @Test
    void aWriteOverTheLimitIs429WithRetryAfter() throws Exception {
        mockMvc.perform(post("/wants/1/close").with(as("user_writer"))).andExpect(status().isOk());
        mockMvc.perform(post("/wants/1/close").with(as("user_writer"))).andExpect(status().isOk());

        mockMvc.perform(post("/wants/1/close").with(as("user_writer")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"));
    }

    @Test
    void aRefusedWriteNeverReachesTheService() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/wants/1/close").with(as("user_refused")));
        }

        then(wantService).should(times(2)).close(eq(1L), any());
    }

    @Test
    void aReadOverTheLimitIs429() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/wants").with(as("user_reader"))).andExpect(status().isOk());
        }

        mockMvc.perform(get("/wants").with(as("user_reader")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void oneUserAtTheLimitLeavesOthersAlone() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/wants/1/close").with(as("user_busy")));
        }
        mockMvc.perform(post("/wants/1/close").with(as("user_busy"))).andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/wants/1/close").with(as("user_quiet"))).andExpect(status().isOk());
    }

    @Test
    void writesAtTheLimitStillRead() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/wants/1/close").with(as("user_both")));
        }
        mockMvc.perform(post("/wants/1/close").with(as("user_both"))).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/wants").with(as("user_both"))).andExpect(status().isOk());
    }

    @Test
    void aRealBearerTokenIsLimitedByItsSubject() throws Exception {
        // jwt() above signs MockMvc in before the chain runs. A real header goes
        // through Spring's token filter instead, so this fails if the limit filter
        // is ever placed ahead of it: there would be no user yet to count.
        given(jwtDecoder.decode("token")).willReturn(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user_bearer")
                .build());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/wants/1/close").header("Authorization", "Bearer token"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/wants/1/close").header("Authorization", "Bearer token"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void requestsWithoutATokenAre401NotCounted() throws Exception {
        // There is no user to count them against; each is refused before the limit.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/wants/1/close")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theHealthCheckIsNeverCounted() {
        RateLimitFilter filter = new RateLimitFilter(null);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/wants"))).isFalse();
    }
}
