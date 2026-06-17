package com.bvisionry.config;

import com.bvisionry.auth.jwt.DownloadTokenAuthenticationFilter;
import com.bvisionry.auth.jwt.JwtAuthenticationFilter;
import com.bvisionry.publicassessment.ratelimit.PublicAssessmentRateLimitFilter;
import com.bvisionry.survey.ratelimit.SurveySubmitRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SurveySubmitRateLimitFilter surveySubmitRateLimitFilter;
    private final PublicAssessmentRateLimitFilter publicAssessmentRateLimitFilter;
    private final DownloadTokenAuthenticationFilter downloadTokenAuthenticationFilter;

    @Value("${bvisionry.cors.allowed-origins:http://localhost:5173,http://localhost:4173,http://localhost:3000,http://localhost:5174}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Double-submit CSRF: Spring writes a non-HttpOnly XSRF-TOKEN cookie
        // (so the SPA can read it via document.cookie) and expects the value
        // echoed back as the X-XSRF-TOKEN header on every state-changing call.
        // Disabling the BREACH XOR handler (Spring 6 default) is required for
        // SPAs because the cookie value would otherwise rotate per-request and
        // not match the header.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(csrfHandler)
                        // Pre-auth entry points: no CSRF cookie exists yet, and
                        // the calls themselves are authenticated by other means
                        // (password, SSO state cookie, single-use invite token).
                        .ignoringRequestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/oauth2/**",
                                "/api/invitations/*/accept",
                                "/api/join/**",
                                "/api/public/surveys/**",
                                "/api/public/assessments/**",
                                // Server-side BFF POST — no CSRF cookie in flight
                                "/api/v1/leads",
                                // Player POST endpoints called through BFF (server-side, no CSRF cookie)
                                "/api/v1/courses/*/enroll",
                                "/api/v1/enrollments/*/content/*/complete",
                                "/api/v1/enrollments/*/content/*/quiz/attempts",
                                "/api/v1/enrollments/*/content/*/position",
                                "/api/v1/courses/*/content/*/assessment/start"
                        )
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Pre-auth entry points only — narrowly scoped so an
                        // expired-session call to /api/auth/me fails fast with
                        // 401 instead of leaking past the filter to a null
                        // @AuthenticationPrincipal in the controller.
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/logout").permitAll()
                        .requestMatchers("/api/auth/oauth2/**").permitAll()
                        .requestMatchers("/api/invitations/*").permitAll()
                        .requestMatchers("/api/invitations/*/accept").permitAll()
                        .requestMatchers("/api/join/**").permitAll()
                        .requestMatchers("/api/public/surveys/**").permitAll()
                        .requestMatchers("/api/public/assessments/**").permitAll()
                        // Lead capture — public POST from the Book-a-Demo BFF
                        .requestMatchers(HttpMethod.POST, "/api/v1/leads").permitAll()
                        // LMS catalog + health: public, read-only.
                        .requestMatchers("/api/v1/health").permitAll()
                        // Public catalog — keep permitAll for list + detail.
                        // Enrollment/learn/progress sub-paths are authenticated and
                        // handled by @PreAuthorize in their controllers.
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses", "/api/v1/courses/{slug}").permitAll()
                        // Public certificate verification — share a cert number to verify authenticity.
                        .requestMatchers(HttpMethod.GET, "/api/v1/certificates/verify/*").permitAll()
                        // Public homepage testimonials — published list only. The /admin
                        // list and all writes are method-secured to SUPER_ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/v1/testimonials").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SUPER_ADMIN")
                        .requestMatchers("/api/pipelines/published").authenticated()
                        .requestMatchers("/api/pipelines/*/simulate").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(401);
                            response.getWriter().write("{\"status\":401,\"message\":\"Unauthorized\"}");
                        })
                )
                // Order: download-token (?token= URL auth) → cookie/Bearer JWT → rate-limit.
                // The download filter is a no-op when there's no ?token, so cookie auth
                // continues to work for everything else.
                .addFilterBefore(downloadTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, DownloadTokenAuthenticationFilter.class)
                .addFilterBefore(surveySubmitRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(publicAssessmentRateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
