package com.bvisionry.businesscard.ratelimit;

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
 * Rate-limits the public, unauthenticated business-card lookup
 * ({@code GET /api/public/business-cards/**}) per client IP. The endpoint
 * serves personal contact PII to anyone with a slug, so without a ceiling it can
 * be scraped or enumerated unthrottled. Mirrors {@code SurveySubmitRateLimitFilter}:
 * a {@link OncePerRequestFilter} resolving the real client IP via
 * {@link ClientIpResolver} and emitting a shared 429 {@code ProblemDetail} shape via
 * {@link ProblemDetailResponseWriter}.
 */
@Component
@RequiredArgsConstructor
public class BusinessCardRateLimitFilter extends OncePerRequestFilter {

    private static final String PATTERN = "/api/public/business-cards/**";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !MATCHER.match(PATTERN, request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);

        try {
            rateLimitService.checkBusinessCardLimit("ip:" + ip);
        } catch (RateLimitExceededException ex) {
            writeTooManyRequests(response, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, String message) throws IOException {
        ProblemDetailResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
