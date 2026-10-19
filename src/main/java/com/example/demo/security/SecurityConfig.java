package com.example.demo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint needs a valid Clerk session token in "Authorization: Bearer",
 * except the health check and Spring's error page.
 *
 * Stateless: no HTTP session, each request proves itself. CSRF protection is
 * off because it guards cookie-based logins, and this API reads credentials
 * only from the Authorization header, which a cross-site form cannot set.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // The deploy script polls this. It reports only UP or DOWN.
                        .requestMatchers("/actuator/health").permitAll()
                        // Errors from authenticated requests are forwarded here;
                        // without this they would turn into a 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
