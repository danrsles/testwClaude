package com.example.demo.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * How a Clerk session token is checked: its signature against the instance's
 * public keys, then expiry, issuer and authorized party.
 *
 * Kept apart from SecurityConfig so controller tests can import the filter
 * chain and mock this one bean instead of reaching Clerk.
 *
 * The keys come from Clerk's JWKS endpoint, fetched on the first request and
 * cached, so startup needs no network. Nothing here is secret: the backend
 * never sees Clerk's secret key.
 */
@Configuration
public class ClerkJwtConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.auth.issuer}") String issuer,
            @Value("${app.auth.authorized-parties}") String authorizedParties) {
        if (issuer.isBlank()) {
            throw new IllegalStateException("app.auth.issuer is blank; set CLERK_ISSUER to the Clerk Frontend API URL");
        }
        // Clerk's iss has no trailing slash, and issuer validation is an exact match.
        String base = issuer.strip().replaceAll("/+$", "");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(base + "/.well-known/jwks.json").build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(base),
                new AuthorizedPartyValidator(parse(authorizedParties))));
        return decoder;
    }

    private static Set<String> parse(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
