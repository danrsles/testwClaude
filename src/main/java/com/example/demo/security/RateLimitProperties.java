package com.example.demo.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.rate-limit.* properties in application.properties.
 *
 * @param enabled  off only in tests, whose cached contexts would otherwise
 *                 share one user's buckets across test methods
 * @param reads    GET requests
 * @param writes   POST, PUT and DELETE requests
 * @param maxUsers how many users' buckets to keep at most; past it the least
 *                 recently seen are dropped, so memory has a ceiling
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(boolean enabled, Limit reads, Limit writes, long maxUsers) {

    /**
     * A token bucket.
     *
     * @param capacity  how many requests can arrive at once
     * @param perMinute how fast spent requests come back, spread evenly over the minute
     */
    public record Limit(long capacity, long perMinute) {
    }
}
