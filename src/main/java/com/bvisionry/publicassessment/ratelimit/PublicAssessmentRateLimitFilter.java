package com.bvisionry.publicassessment.ratelimit;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.ErrorResponse;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces the public-assessment rate limit BEFORE request body deserialization
 * and bean validation, so malformed payloads also consume tokens. Matches the
 * two abuse-sensitive POSTs of the anonymous flow:
 * {@code POST /api/public/assessments/by-token/{token}/sessions} (session
 * create — gates link-token brute force and response-slot exhaustion) and
 * {@code POST /api/public/assessments/sessions/{accessToken}/submit} (each
 * submit dispatches an AI evaluation).
 */
@Component
@RequiredArgsConstructor
public class PublicAssessmentRateLimitFilter extends OncePerRequestFilter {

    private static final String SESSION_CREATE_PATTERN = "/api/public/assessments/by-token/*/sessions";
    private static final String SUBMIT_PATTERN = "/api/public/assessments/sessions/*/submit";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || (!MATCHER.match(SESSION_CREATE_PATTERN, uri)
                        && !MATCHER.match(SUBMIT_PATTERN, uri))) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        String token = extractToken(uri);

        try {
            rateLimitService.checkPublicAssessmentLimit("ip:" + ip);
            rateLimitService.checkPublicAssessmentLimit("token:" + token + ":" + ip);
        } catch (RateLimitExceededException ex) {
            writeTooManyRequests(response, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Pulls the path token out of either matched shape: the link token after
     * {@code by-token} on session create, or the session accessToken after
     * {@code sessions} on submit.
     */
    private static String extractToken(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("by-token".equals(parts[i]) || "sessions".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private void writeTooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        JSON.writeValue(response.getWriter(),
                new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), message));
    }
}
