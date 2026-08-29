package com.bvisionry.config;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.common.web.ProblemDetailResponseWriter;
import com.bvisionry.common.web.RequestPaths;
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
 * Enforces the public-exercise submit rate limit BEFORE request body
 * deserialization and bean validation, so malformed payloads also consume
 * tokens. Matches exactly
 * {@code POST /api/public/exercises/by-token/{token}/responses}.
 *
 * <p>The survey twin of this lives in {@code survey.ratelimit}; this one sits
 * in {@code config} with the other cross-cutting rate-limit filters because
 * the exercise feature does not otherwise depend on {@code aiconfig}, and the
 * architecture rules keep it that way.
 */
@Component
@RequiredArgsConstructor
public class PublicExerciseSubmitRateLimitFilter extends OncePerRequestFilter {

    private static final String PATTERN = "/api/public/exercises/by-token/*/responses";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = RequestPaths.decoded(request);
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !MATCHER.match(PATTERN, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        String token = extractToken(path);

        try {
            rateLimitService.checkExerciseSubmitLimit("ip:" + ip);
            rateLimitService.checkExerciseSubmitLimit("token:" + token + ":" + ip);
        } catch (RateLimitExceededException ex) {
            ProblemDetailResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String extractToken(String uri) {
        // /api/public/exercises/by-token/{token}/responses
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("by-token".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return "";
    }
}
