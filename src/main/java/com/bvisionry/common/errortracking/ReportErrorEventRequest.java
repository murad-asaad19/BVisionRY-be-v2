package com.bvisionry.common.errortracking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An unhandled error forwarded from the web tier. Always recorded as
 * {@link ErrorSource#WEB} — the source is never client-supplied, so a report can
 * never masquerade as a backend exception.
 *
 * <p>This endpoint is public and CSRF-exempt, so every field is length-capped via
 * {@link Size}, mirroring {@code ContactRequest}. The caps are the FIRST bound;
 * {@code ErrorEventRecorder} truncates again regardless, so a client that bypasses
 * validation still cannot write an unbounded row.
 */
public record ReportErrorEventRequest(

        @NotBlank(message = "Exception type is required")
        @Size(max = 255, message = "Exception type must be at most 255 characters")
        String exceptionType,

        @Size(max = 4000, message = "Message must be at most 4000 characters")
        String message,

        @Size(max = 20000, message = "Stack trace must be at most 20000 characters")
        String stackTrace,

        @Size(max = 2000, message = "Request path must be at most 2000 characters")
        String requestPath,

        @Size(max = 16, message = "Request method must be at most 16 characters")
        String requestMethod,

        /** X-Request-Id of the failing request — the cross-tier join key. */
        @Size(max = 64, message = "Request id must be at most 64 characters")
        String requestId,

        /** Next.js error digest. Never a join key; see the V145 comment. */
        @Size(max = 64, message = "Digest must be at most 64 characters")
        String digest) {
}
