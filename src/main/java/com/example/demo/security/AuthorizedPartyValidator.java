package com.example.demo.security;

import java.util.Set;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts a Clerk token only if its "azp" claim names one of our own origins.
 *
 * Clerk puts the browser origin that requested the token in azp. Checking it is
 * what Clerk's own backend SDKs do, and it stops a token issued to some other
 * site on the same Clerk instance being replayed here. A token with no azp is
 * rejected, so the check fails closed.
 */
public class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "The token was not issued to an allowed origin", null);

    private final Set<String> allowed;

    public AuthorizedPartyValidator(Set<String> allowed) {
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("At least one authorized party is required");
        }
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String azp = token.getClaimAsString("azp");
        return azp != null && allowed.contains(azp)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
