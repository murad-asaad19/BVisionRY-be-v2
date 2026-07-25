package com.bvisionry.config;

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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Second layer on the public error-report ingest, mirroring
 * {@code SurveySubmitRateLimitFilter} / {@code BusinessCardRateLimitFilter}:
 * {@code POST /api/v1/error-events} is {@code permitAll} and CSRF-exempt, so
 * without a per-IP ceiling one browser stuck in a render-crash loop (or a bot)
 * could flood the store the whole run depends on for its regression signal.
 *
 * <p>Runs BEFORE body deserialization, so a malformed flood consumes tokens too.
 * The key is {@link ClientIpResolver}'s resolved address — the BFF-forwarded real
 * client IP when the proxy secret authenticates it, otherwise the right-counted
 * XFF/peer address, so a spoofed header cannot rotate out of the bucket.
 *
 * <p><b>Why it lives in {@code config} and not beside the controller.</b>
 * {@link RateLimitService} is in the {@code aiconfig} feature; the controller is in
 * {@code common}, which ArchUnit rule 3 forbids from depending on any feature, and
 * a NEW feature-to-feature edge would break the frozen rule 1. {@code config} is a
 * declared shared/wiring package and is exempt from both — the same reason
 * {@code SecurityConfig} already wires every other rate-limit filter from here.
 */
@Component
@RequiredArgsConstructor
public class ErrorEventRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/v1/error-events";

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // POST only: the GET on this path is the SUPER_ADMIN read and is already
        // gated by authentication + @PreAuthorize.
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            rateLimitService.checkErrorReportLimit("ip:" + clientIpResolver.resolve(request));
        } catch (RateLimitExceededException ex) {
            ProblemDetailResponseWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
