package com.bvisionry.common.errortracking;

import java.time.Instant;
import java.util.UUID;

/** One aggregated error as returned by the SUPER_ADMIN query endpoint. */
public record ErrorEventResponse(
        UUID id,
        ErrorSource source,
        String exceptionType,
        String message,
        String stackTrace,
        String requestPath,
        String requestMethod,
        String requestId,
        String digest,
        /** When the store accepted the row. Named for what it actually is: the recorder
         *  writes asynchronously, so this trails the true occurrence by milliseconds. */
        Instant recordedAt) {

    public static ErrorEventResponse from(ErrorEvent event) {
        return new ErrorEventResponse(
                event.getId(),
                event.getSource(),
                event.getExceptionType(),
                event.getMessage(),
                event.getStackTrace(),
                event.getRequestPath(),
                event.getRequestMethod(),
                event.getRequestId(),
                event.getDigest(),
                event.getCreatedAt());
    }
}
