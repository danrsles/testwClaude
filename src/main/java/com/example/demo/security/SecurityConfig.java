package com.example.demo.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint needs a valid Clerk session token in "Authorization: Bearer",
 * except the health check and Spring's error page.
 *
 * Stateless: no HTTP session, each request proves itself. CSRF protection is
 * off because it guards cookie-based logins, and this API reads credentials
 * only from the Authorization header, which a cross-site form cannot set.
 *
 * Each user's requests are then rate limited (RateLimitFilter), keyed by the
 * verified token, so the limit filter goes straight after the token filter.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, RateLimitProperties rateLimits) throws Exception {
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
        if (rateLimits.enabled()) {
            http.addFilterAfter(new RateLimitFilter(new RateLimiter(rateLimits)), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }
}
