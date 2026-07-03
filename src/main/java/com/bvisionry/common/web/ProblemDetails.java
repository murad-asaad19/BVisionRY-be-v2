package com.bvisionry.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.time.Instant;

/**
 * Builds RFC-7807 {@link ProblemDetail} bodies in the single canonical shape used
 * across the whole application — {@code {type, status, detail, timestamp}} — so
 * every error surface stays byte-for-byte consistent: MVC {@code @ExceptionHandler}s
 * (via {@link com.bvisionry.common.exception.GlobalExceptionHandler}), the pre-MVC
 * rate-limit filters and the security authentication entry point (both via
 * {@link ProblemDetailResponseWriter}).
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    /**
     * Creates a {@link ProblemDetail} for {@code status} carrying {@code detail} and a
     * {@code timestamp} extension property set to {@link Instant#now()}. Callers may add
     * further extension properties (e.g. {@code fieldErrors}) to the returned instance.
     */
    public static ProblemDetail of(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
