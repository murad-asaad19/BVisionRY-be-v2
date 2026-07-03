package com.bvisionry.survey.ratelimit;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.web.ProblemDetailResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces the public-survey submit rate limit BEFORE request body deserialization
 * and bean validation, so malformed payloads also consume tokens. Matches exactly
 * {@code POST /api/public/surveys/by-token/{token}/responses}.
 */
@Component
@RequiredArgsConstructor
public class SurveySubmitRateLimitFilter extends OncePerRequestFilter {

    private static final String PATTERN = "/api/public/surveys/by-token/*/responses";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !MATCHER.match(PATTERN, request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        String token = extractToken(request.getRequestURI());

        try {
            rateLimitService.checkSurveySubmitLimit("ip:" + ip);
            rateLimitService.checkSurveySubmitLimit("token:" + token + ":" + ip);
        } catch (RateLimitExceededException ex) {
            writeTooManyRequests(response, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String extractToken(String uri) {
        // /api/public/surveys/by-token/{token}/responses
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("by-token".equals(parts[i])) return parts[i + 1];
        }
        return "";
    }

    private void writeTooManyRequests(HttpServletResponse response, String message) throws IOException {
        ProblemDetailResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
