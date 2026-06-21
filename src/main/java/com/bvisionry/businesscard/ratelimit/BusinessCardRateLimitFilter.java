package com.bvisionry.businesscard.ratelimit;

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
 * Rate-limits the public, unauthenticated business-card lookup
 * ({@code GET /api/public/business-cards/**}) per client IP. The endpoint
 * serves personal contact PII to anyone with a slug, so without a ceiling it can
 * be scraped or enumerated unthrottled. Mirrors {@code SurveySubmitRateLimitFilter}:
 * a {@link OncePerRequestFilter} resolving the real client IP via
 * {@link ClientIpResolver} and emitting the shared 429 {@link ErrorResponse} shape.
 */
@Component
@RequiredArgsConstructor
public class BusinessCardRateLimitFilter extends OncePerRequestFilter {

    private static final String PATTERN = "/api/public/business-cards/**";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        JSON.writeValue(response.getWriter(),
                new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), message));
    }
}
