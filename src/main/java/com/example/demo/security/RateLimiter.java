package com.example.demo.security;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

/**
 * Two token buckets per user, one for reads and one for writes, so a burst
 * of saves never stops the feed from loading.
 *
 * A bucket holds up to `capacity` tokens and refills at `perMinute`, a token
 * at a time (greedily, not all at once each minute). A request spends one;
 * an empty bucket says how long until the next comes back.
 *
 * Buckets live in memory, in a Caffeine cache keyed by Clerk user id. A user
 * idle for 10 minutes is dropped, by which time their buckets would be full
 * again anyway, so dropping them changes nothing they could notice. That is
 * exact only while there is one API instance; a second would keep its own
 * buckets and let each user through twice as often.
 */
public class RateLimiter {

    /** Longer than any bucket takes to refill completely at these limits. */
    private static final Duration IDLE = Duration.ofMinutes(10);

    private record Buckets(Bucket reads, Bucket writes) {
    }

    /**
     * @param allowed     whether the request may go ahead
     * @param retryAfter  when refused, whole seconds until a token is back (at least 1)
     */
    public record Decision(boolean allowed, long retryAfter) {
    }

    private final RateLimitProperties limits;
    private final TimeMeter time;
    private final Cache<String, Buckets> buckets;

    public RateLimiter(RateLimitProperties limits) {
        this(limits, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** With a clock of the caller's choosing, so tests can move time. */
    RateLimiter(RateLimitProperties limits, TimeMeter time) {
        this.limits = limits;
        this.time = time;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(IDLE)
                .maximumSize(limits.maxUsers())
                .build();
    }

    public Decision tryWrite(String userId) {
        return spend(bucketsOf(userId).writes());
    }

    public Decision tryRead(String userId) {
        return spend(bucketsOf(userId).reads());
    }

    private Buckets bucketsOf(String userId) {
        return buckets.get(userId, id -> new Buckets(bucket(limits.reads()), bucket(limits.writes())));
    }

    private Bucket bucket(RateLimitProperties.Limit limit) {
        return Bucket.builder()
                .addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.perMinute(), Duration.ofMinutes(1)))
                .withCustomTimePrecision(time)
                .build();
    }

    private static Decision spend(Bucket bucket) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0);
        }
        // Rounded up: a client that waits exactly this long finds a token.
        long seconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).plusNanos(999_999_999).toSeconds();
        return new Decision(false, Math.max(1, seconds));
    }
}
