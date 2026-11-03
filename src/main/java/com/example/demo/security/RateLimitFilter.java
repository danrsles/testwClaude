package com.example.demo.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a user's request with 429 and a Retry-After header once they have
 * spent their reads or their writes (see RateLimiter).
 *
 * SecurityConfig places it after Spring's bearer-token filter, so by the time
 * it runs the token has been verified and its subject, the Clerk user id, is
 * the key. A request without a valid token never gets here; it is already a
 * 401. The health check is never counted, whoever calls it.
 *
 * Deliberately not a @Component: Spring Boot registers every Filter bean as a
 * servlet filter of its own, which would run it a second time outside the
 * security chain, before there is any user to key by.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String userId = token.getToken().getSubject();
        RateLimiter.Decision decision = isRead(request) ? limiter.tryRead(userId) : limiter.tryWrite(userId);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfter()));
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    /** GET, and HEAD and OPTIONS, which change nothing either. Everything else is a write. */
    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
    }
}
