package com.example.demo.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.github.bucket4j.TimeMeter;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** The deployed limits. */
    private static final RateLimitProperties LIMITS = new RateLimitProperties(true,
            new RateLimitProperties.Limit(120, 120),
            new RateLimitProperties.Limit(10, 30),
            1000);

    /** A clock that moves only when a test says so. */
    private static class FakeTime implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private final FakeTime time = new FakeTime();
    private final RateLimiter limiter = new RateLimiter(LIMITS, time);

    private void spendWrites(String userId, int count) {
        for (int i = 0; i < count; i++) {
            assertThat(limiter.tryWrite(userId).allowed()).as("write %d", i + 1).isTrue();
        }
    }

    @Test
    void tenWritesAtOnceThenTheEleventhWaitsTwoSeconds() {
        spendWrites("user_a", 10);

        RateLimiter.Decision eleventh = limiter.tryWrite("user_a");

        assertThat(eleventh.allowed()).isFalse();
        // 30 a minute is one every 2 s.
        assertThat(eleventh.retryAfter()).isEqualTo(2);
    }

    @Test
    void aWriteComesBackAfterRetryAfter() {
        spendWrites("user_a", 10);
        assertThat(limiter.tryWrite("user_a").allowed()).isFalse();

        time.advance(Duration.ofSeconds(2));

        assertThat(limiter.tryWrite("user_a").allowed()).isTrue();
        assertThat(limiter.tryWrite("user_a").allowed()).isFalse();
    }

    @Test
    void retryAfterRoundsUpToAWholeSecond() {
        spendWrites("user_a", 10);
        time.advance(Duration.ofMillis(500));

        // 1.5 s left: waiting only 1 s would find the bucket still empty.
        assertThat(limiter.tryWrite("user_a").retryAfter()).isEqualTo(2);
    }

    @Test
    void writesRefillAtThirtyAMinuteButHoldTen() {
        spendWrites("user_a", 10);

        time.advance(Duration.ofMinutes(1));

        // A minute refills 30 tokens, but the bucket holds only 10.
        spendWrites("user_a", 10);
        assertThat(limiter.tryWrite("user_a").allowed()).isFalse();
    }

    @Test
    void usersHaveTheirOwnBuckets() {
        spendWrites("user_a", 10);
        assertThat(limiter.tryWrite("user_a").allowed()).isFalse();

        assertThat(limiter.tryWrite("user_b").allowed()).isTrue();
    }

    @Test
    void spentWritesLeaveReadsAlone() {
        spendWrites("user_a", 10);
        assertThat(limiter.tryWrite("user_a").allowed()).isFalse();

        assertThat(limiter.tryRead("user_a").allowed()).isTrue();
    }

    @Test
    void oneHundredTwentyReadsAtOnceThenOneEveryHalfSecond() {
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.tryRead("user_a").allowed()).as("read %d", i + 1).isTrue();
        }

        RateLimiter.Decision refused = limiter.tryRead("user_a");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(1);

        time.advance(Duration.ofMillis(500));
        assertThat(limiter.tryRead("user_a").allowed()).isTrue();
        // Spent reads leave writes alone too.
        assertThat(limiter.tryWrite("user_a").allowed()).isTrue();
    }
}
