package com.example.demo.services;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes wants at their deadline, once a minute. It only records closed_at
 * and sends WANT_EXPIRED: a want past its deadline already refuses signups
 * and edits, because Want.getStatus checks the deadline itself. So a late or
 * missed run delays the notification, never what people may do.
 *
 * fixedDelay counts from the end of the previous run, so runs never overlap.
 * After a restart the first run catches up on everything missed. A failed
 * run is logged by Spring and simply tried again a minute later.
 *
 * Switched by app.expiry.enabled. It is off in tests, which call
 * WantService.expireDue themselves, so the job can never race them.
 */
@Component
@ConditionalOnProperty(name = "app.expiry.enabled", havingValue = "true")
public class WantExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(WantExpiryJob.class);

    private final WantService wantService;

    public WantExpiryJob(WantService wantService) {
        this.wantService = wantService;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void expireDue() {
        int sent = wantService.expireDue();
        if (sent > 0) {
            log.info("Closed wants at their deadline: {} notifications sent", sent);
        }
    }
}
