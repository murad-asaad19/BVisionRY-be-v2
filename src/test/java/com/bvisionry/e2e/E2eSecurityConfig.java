package com.bvisionry.e2e;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Adds a Spring Security filter chain that opens up {@code /test/**} so Playwright's
 * globalSetup can call {@code /test/reset} without a JWT. Ordered ahead of the
 * production chain in {@link com.bvisionry.config.SecurityConfig}, so only the
 * {@code /test/**} subtree is affected — every other request still goes through
 * the main chain unchanged.
 */
@Configuration
@Profile("e2e")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class E2eSecurityConfig {

    @Bean
    public SecurityFilterChain e2eTestSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/test/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
