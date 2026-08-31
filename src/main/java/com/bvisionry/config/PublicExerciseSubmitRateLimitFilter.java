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
import java.util.function.Consumer;

/**
 * Enforces the public-exercise submit rate limit BEFORE request body
 * deserialization and bean validation, so malformed payloads also consume
 * tokens. Matches {@code POST /api/public/exercises/by-token/{token}/responses}
 * and its {@code /pdf} sibling — on separate budgets (see {@link #PDF_PATTERN}).
 *
 * <p>The survey twin of this lives in {@code survey.ratelimit}; this one sits
 * in {@code config} with the other cross-cutting rate-limit filters because
 * the exercise feature does not otherwise depend on {@code aiconfig}, and the
 * architecture rules keep it that way.
 */
@Component
@RequiredArgsConstructor
public class PublicExerciseSubmitRateLimitFilter extends OncePerRequestFilter {

    private static final String SUBMIT_PATTERN = "/api/public/exercises/by-token/*/responses";
    /**
     * The PDF render persists nothing, but it is the most expensive thing an
     * anonymous caller can ask this app to do (Flying Saucer lays out a whole
     * document per request), so it is limited too — on its OWN budget. A
     * workshop room is one NAT IP: sharing the submit budget would let a few
     * people downloading their copy 429 the next person's submit, losing an
     * answer to save a printout.
     */
    private static final String PDF_PATTERN = "/api/public/exercises/by-token/*/pdf";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = RequestPaths.decoded(request);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        boolean submit = post && MATCHER.match(SUBMIT_PATTERN, path);
        boolean pdf = post && MATCHER.match(PDF_PATTERN, path);
        if (!submit && !pdf) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        String token = extractToken(path);
        Consumer<String> limit = submit
                ? rateLimitService::checkExerciseSubmitLimit
                : rateLimitService::checkExercisePdfLimit;

        try {
            limit.accept("ip:" + ip);
            limit.accept("token:" + token + ":" + ip);
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
