package com.example.demo.loadtest;

import com.example.demo.DemoApplication;

import org.springframework.boot.SpringApplication;

/**
 * The API as the load test runs it: mvn spring-boot:test-run, which finds
 * this main on the test classpath. It is the normal application plus
 * LoadTestSecurityConfiguration, on the local database.
 *
 * The local profile is named outright, since adding any profile switches
 * the default one off; loadtest (application-loadtest.properties) sets what
 * differs from a normal local run, such as the pool size.
 */
public final class LoadTestApplication {

    private LoadTestApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.from(DemoApplication::main)
                .with(LoadTestSecurityConfiguration.class)
                .withAdditionalProfiles("local", "loadtest")
                .run(args);
    }
}
