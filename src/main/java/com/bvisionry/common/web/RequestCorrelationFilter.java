package com.bvisionry.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stamps every request with a correlation id so a single request can be followed
 * across the two-service topology (Next.js BFF on Vercel &rarr; this Spring Boot API
 * on Railway) purely through log lines. The BFF generates an {@code X-Request-Id}
 * per proxied request and forwards it here; we surface it in the MDC (and therefore
 * in the prod JSON logs and dev console) and echo it back on the response so a
 * user-reported error can be traced end to end.
 *
 * <p><b>This is the pragmatic MDC + header layer, not distributed tracing.</b> Full
 * micrometer-tracing / OpenTelemetry is deliberately deferred until a tracing backend
 * exists. When it lands it can replace this by populating the SAME reserved log slot
 * ({@code LOG_CORRELATION_PATTERN} / the {@code mdc} block) with traceId/spanId.
 *
 * <p><b>Filter ordering &mdash; {@link Ordered#HIGHEST_PRECEDENCE}.</b> As a plain
 * {@code @Component Filter} this is auto-registered by Spring Boot directly with the
 * servlet container, OUTSIDE and wrapping Spring Security's {@code FilterChainProxy}
 * (registered by Boot at {@code SecurityProperties.DEFAULT_FILTER_ORDER == -100}).
 * {@code HIGHEST_PRECEDENCE} ({@link Integer#MIN_VALUE}, far below {@code -100})
 * guarantees we run FIRST. This matters for correctness: the 401 emitted by the
 * security {@code authenticationEntryPoint} is produced INSIDE the security chain,
 * which is nested inside this filter. Because we populate the MDC and set the
 * response header BEFORE calling {@code filterChain.doFilter(...)} &mdash; i.e. before
 * the security chain runs and commits the 401 &mdash; the correlation id is present
 * on unauthenticated responses too, and the MDC key stays populated for the whole
 * request (we only clear it in {@code finally}, after the entire chain returns).
 * Extending {@link OncePerRequestFilter} additionally makes execution idempotent even
 * if the bean were ever pulled into another chain.
 *
 * <p><b>Untrusted input.</b> {@code X-Request-Id} is attacker-controlled and flows
 * straight into log files, so it must never carry a log-injection payload (CR/LF,
 * control chars) or unbounded length. We accept an incoming id ONLY when it matches a
 * strict safe charset and length ({@link #SAFE_REQUEST_ID}); anything else &mdash; and
 * an absent header &mdash; is REPLACED with a freshly generated id. The accepted
 * charset deliberately excludes whitespace and newlines, so a validated id cannot
 * forge extra log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /** Incoming/outgoing header carrying the cross-service correlation id. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key surfaced by the dev console pattern and the prod JSON encoder. */
    public static final String MDC_KEY = "requestId";

    /**
     * Safe charset/length for an incoming id: up to 64 chars of {@code [A-Za-z0-9._-]}.
     * Broad enough to accept a UUID (with or without dashes) or the BFF's generated
     * id, narrow enough that a validated value can never inject a newline into a log
     * line or bloat it. Anything outside this is discarded, not sanitised.
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));

        // Set BEFORE the chain runs: downstream (including Spring Security's 401 entry
        // point) may commit the response, after which headers can no longer be added.
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Clear only after the whole chain returns so the id is present for every
            // log line of this request; MDC is thread-bound and threads are pooled, so
            // a leaked key would bleed into the next request served by this thread.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Returns the incoming id when it is present and passes {@link #SAFE_REQUEST_ID},
     * otherwise a freshly generated one. A UUID string ({@code [0-9a-f-]}, 36 chars)
     * itself satisfies the safe charset, so re-validation on the next hop is stable.
     */
    private String resolveRequestId(String incoming) {
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
