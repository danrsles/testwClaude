package com.example.demo.loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The load test's stand-in for Clerk: tokens signed with the local test key
 * get in, and nothing else does, so the rest of the security chain is tested
 * as it runs for real.
 */
class LoadTestSecurityConfigurationTest {

    private static KeyPair testKey;

    private static KeyPair otherKey;

    @TempDir
    static Path keys;

    private static JwtDecoder decoder;

    @BeforeAll
    static void makeKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        testKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
        decoder = new LoadTestSecurityConfiguration().loadTestJwtDecoder(new MockEnvironment(), pem(testKey));
    }

    /** The public key as make-tokens.mjs writes it: SPKI in PEM. */
    private static Path pem(KeyPair key) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getPublic().getEncoded());
        Path file = keys.resolve("public-" + System.nanoTime() + ".pem");
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
        return file;
    }

    private static String token(KeyPair key, String issuer, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("load_user_7")
                .claim("username", "load_7")
                .issuer(issuer)
                .issueTime(Date.from(expiresAt.minusSeconds(7200)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static String validToken(KeyPair key) throws Exception {
        return token(key, LoadTestSecurityConfiguration.ISSUER, Instant.now().plusSeconds(3600));
    }

    @Test
    void acceptsATokenSignedWithTheTestKey() throws Exception {
        Jwt jwt = decoder.decode(validToken(testKey));

        assertThat(jwt.getSubject()).isEqualTo("load_user_7");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        String forged = validToken(otherKey);

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        // Clerk's issuer: even signed with the test key, a token claiming to be
        // Clerk's is refused, so the two can never be mistaken for each other.
        String clerkIssued = token(testKey, "https://enabled-walleye-998.clerk.accounts.dev",
                Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> decoder.decode(clerkIssued)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        // Past the default 60 seconds of allowed clock skew.
        String expired = token(testKey, LoadTestSecurityConfiguration.ISSUER, Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithTheProdProfile() throws Exception {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        Path key = pem(testKey);

        assertThatThrownBy(() -> new LoadTestSecurityConfiguration().loadTestJwtDecoder(prod, key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    void readsTheSubjectAndUsernameLikeAClerkToken() throws Exception {
        Jwt jwt = decoder.decode(validToken(testKey));

        // The two claims Identities.of reads to know who is asking.
        assertThat(jwt.getSubject()).isEqualTo("load_user_7");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("load_7");
    }
}
