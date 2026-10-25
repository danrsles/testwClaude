package com.example.demo.models;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a want's status follows from its timestamps and the time now. */
class WantTest {

    private static final Instant DEADLINE = Instant.parse("2026-10-24T18:00:00Z");

    private final User dani = new User("dani", "user_dani");

    private Want withDeadline() {
        Want want = new Want("Pizza Friday", Category.FOOD, dani, 4);
        want.setExpiresAt(DEADLINE);
        return want;
    }

    @Test
    void aWantPastItsDeadlineIsClosed() {
        // At the deadline counts as past it, before anything has written closed_at.
        Want want = withDeadline();

        assertThat(want.getStatus(DEADLINE)).isEqualTo(WantStatus.CLOSED);
        assertThat(want.getStatus(DEADLINE.plusSeconds(1))).isEqualTo(WantStatus.CLOSED);
    }

    @Test
    void aWantBeforeItsDeadlineIsActive() {
        assertThat(withDeadline().getStatus(DEADLINE.minusSeconds(1))).isEqualTo(WantStatus.ACTIVE);
    }

    @Test
    void cancelledAndEndedOutrankAPassedDeadline() {
        Want cancelled = withDeadline();
        cancelled.cancel(DEADLINE.minusSeconds(60));
        Want ended = withDeadline();
        ended.start(DEADLINE.minusSeconds(120));
        ended.end(DEADLINE.minusSeconds(60));

        assertThat(cancelled.getStatus(DEADLINE.plusSeconds(1))).isEqualTo(WantStatus.CANCELLED);
        assertThat(ended.getStatus(DEADLINE.plusSeconds(1))).isEqualTo(WantStatus.ENDED);
    }
}
