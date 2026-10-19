package com.example.demo.security;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizedPartyValidatorTest {

    private final AuthorizedPartyValidator validator =
            new AuthorizedPartyValidator(Set.of("http://localhost:5173", "https://example.cloudfront.net"));

    private static Jwt token(String azp) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("user_dani")
                .issuedAt(Instant.now());
        if (azp != null) {
            builder.claim("azp", azp);
        }
        return builder.build();
    }

    @Test
    void acceptsAnAllowedOrigin() {
        assertThat(validator.validate(token("https://example.cloudfront.net")).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnotherOrigin() {
        assertThat(validator.validate(token("https://evil.example")).hasErrors()).isTrue();
    }

    @Test
    void rejectsATokenWithoutAzp() {
        assertThat(validator.validate(token(null)).hasErrors()).isTrue();
    }

    @Test
    void refusesToStartWithNoAllowedOrigins() {
        assertThatThrownBy(() -> new AuthorizedPartyValidator(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
