package com.bvisionry.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

import java.io.IOException;

/**
 * Writes an RFC-7807 {@link ProblemDetail} body directly to a servlet response, for
 * the handful of places that run before Spring MVC's exception-handling machinery
 * takes over (e.g. rate-limit filters ahead of the security chain, and the security
 * authentication entry point), where
 * {@link com.bvisionry.common.exception.GlobalExceptionHandler} never gets a chance
 * to intervene. A plain static utility (not a Spring bean) so it can be called from
 * filters without pulling an extra constructor dependency into web test slices.
 *
 * <p>The body is built via {@link ProblemDetails#of} so it carries the same
 * {@code {type, status, detail, timestamp}} shape as the MVC exception-handling path.
 *
 * <p>Registers {@link ProblemDetailJacksonMixin} on its own {@link ObjectMapper} so
 * extension properties (set via {@link ProblemDetail#setProperty}) serialize as
 * top-level JSON fields — the same shape Spring's own {@code ProblemDetail} HTTP
 * message conversion produces — rather than nesting under a raw {@code properties}
 * map, which is what a plain, un-mixed-in {@code ObjectMapper} would otherwise do.
 */
public final class ProblemDetailResponseWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    private ProblemDetailResponseWriter() {
    }

    /**
     * Writes {@code status} + {@code detail} (plus the canonical {@code timestamp})
     * as an {@code application/problem+json} body to {@code response}.
     */
    public static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, detail);
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        JSON.writeValue(response.getWriter(), problem);
    }
}
