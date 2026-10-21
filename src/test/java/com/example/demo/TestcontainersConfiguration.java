package com.example.demo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway PostgreSQL for database tests (*IT.java), the same image as
 * compose.yaml, so tests run against what development and Aiven run.
 *
 * As a bean, the container lives as long as the Spring context, and Spring
 * reuses one context for every test class with the same setup, so they share
 * one container rather than starting one each. @ServiceConnection points the
 * datasource at it, and Flyway migrates it from empty at startup, exactly as
 * on a fresh database. Nothing touches the compose database on port 5433.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        // Testcontainers knows the official postgres image by name, not
        // postgis/postgis, so it is told the two are compatible, as the label
        // in compose.yaml tells Spring.
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"));
    }
}
