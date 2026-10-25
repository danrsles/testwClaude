package com.example.demo;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling runs WantExpiryJob; see app.expiry.enabled.
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * The time, for anything that depends on it, such as whether a deadline
     * has passed. Services take it rather than calling Instant.now(), so unit
     * tests can hand them Clock.fixed(...) and pin "now" to the second.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
