package com.example.demo.loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * For the load test only: trusts tokens signed with a local test key instead
 * of Clerk's, so k6 can sign in as a thousand made-up users.
 *
 * Only the key changes. The signature, expiry and issuer are still checked,
 * and everything after the decoder (Identities.of, ownership, row locks)
 * runs as it does for real. @Primary makes Spring Security use this decoder;
 * ClerkJwtConfig's is still built, and unused.
 *
 * It lives in src/test, so it is never in the jar or the image: whoever holds
 * the test private key can sign in as anyone. As a @TestConfiguration it is
 * skipped by component scanning in tests, so only LoadTestApplication adds it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LoadTestSecurityConfiguration {

    /** Not Clerk's, and .invalid can never resolve, so neither kind of token passes the other's check. */
    static final String ISSUER = "http://loadtest.invalid";

    @Bean
    @Primary
    JwtDecoder loadTestJwtDecoder(Environment environment,
            @Value("${loadtest.public-key:loadtest/keys/public.pem}") Path publicKey) {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "The load-test key must never be trusted with the prod profile, which is the real database");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(read(publicKey)).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /** An SPKI public key in PEM, as make-tokens.mjs writes it. */
    private static RSAPublicKey read(Path pem) {
        try {
            String body = Files.readString(pem).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (IOException e) {
            throw new IllegalStateException("No load-test key at " + pem.toAbsolutePath()
                    + "; run node loadtest/make-tokens.mjs first", e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Not an RSA public key: " + pem.toAbsolutePath(), e);
        }
    }
}
