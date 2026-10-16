package com.example.demo.controllers;

import java.time.Instant;

import com.example.demo.services.ClerkIdentity;

import org.springframework.security.oauth2.jwt.Jwt;

/** Turns a verified token into the identity the services work with, so services never see Spring Security types. */
final class Identities {

    private Identities() {
    }

    static ClerkIdentity of(Jwt jwt) {
        return new ClerkIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsString("email"),
                epochSeconds(jwt.getClaim("lastLogin")));
    }

    /**
     * Clerk renders user.last_sign_in_at as Unix seconds, as a number even
     * though the template quotes the shortcode. Spring converts only the
     * standard time claims (exp, iat, nbf) to Instant, so a custom one arrives
     * as a plain number. A numeric string is accepted too; anything else is
     * treated as absent rather than failing the request.
     */
    private static Instant epochSeconds(Object claim) {
        if (claim instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        if (claim instanceof String text && text.matches("\\d{1,12}")) {
            return Instant.ofEpochSecond(Long.parseLong(text));
        }
        return null;
    }
}
