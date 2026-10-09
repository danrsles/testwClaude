package com.example.demo.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that did not come through our CloudFront distribution.
 *
 * The security group already admits only CloudFront's address ranges, but those
 * are shared by every CloudFront customer, so anyone could point their own
 * distribution at this server. Ours adds a secret X-Origin-Secret header; a
 * request without the right value gets a bare 403.
 *
 * Switched on by app.origin-check.enabled, which only the prod profile sets.
 * The switch is separate from the secret so the check fails closed: when it is
 * on, a blank secret stops startup instead of quietly letting everything in.
 */
@Component
public class OriginSecretFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Origin-Secret";

    private final boolean enabled;
    private final byte[] expected;

    public OriginSecretFilter(
            @Value("${app.origin-check.enabled:false}") boolean enabled,
            @Value("${app.origin-secret:}") String secret) {
        if (enabled && secret.isBlank()) {
            throw new IllegalStateException(
                    "app.origin-check.enabled is true but app.origin-secret is blank; "
                            + "set ORIGIN_SECRET, or disable the check");
        }
        this.enabled = enabled;
        this.expected = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpStatus.FORBIDDEN.value());
    }

    /** Constant-time, so the response time does not reveal how much of a guess was right. */
    private boolean matches(String provided) {
        return provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    }
}
