package com.bvisionry.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * An unmatched route — e.g. a stale client calling {@code /api/me} instead of
     * {@code /api/auth/me}. Spring's {@code ResourceHttpRequestHandler} raises this
     * when no controller matches and no static resource is found. Without this
     * dedicated handler it falls through to {@link #handleGeneral(Exception)} and
     * becomes a misleading 500 with a full stack trace; an unknown URL is a benign
     * 404. Logged at DEBUG so a probing/scanner request doesn't spam ERROR.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.debug("No resource for request: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "The requested resource was not found.");
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalOperationException.class)
    public ProblemDetail handleIllegalOperation(IllegalOperationException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * A unique/foreign-key constraint collision — most notably two concurrent
     * autosaves racing to insert the first answer for the same
     * (submission, question) pair past the V86 unique index. Without this it falls
     * through to the 500 catch-all; it is really a transient write conflict, so
     * answer 409 and let the client retry rather than surfacing a server error.
     * The DB message is deliberately not echoed (it can leak schema details).
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "This change conflicts with a concurrent update. Please retry.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // First-wins: if a field has multiple violations, keep the first reported message.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Validation failed for " + fieldErrors.size() + " field(s).");
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    /**
     * A request body that cannot be deserialized — malformed JSON, or a field
     * whose value doesn't fit the target type (e.g. a bare "2026-06-23" date
     * where an ISO {@code Instant} is required). This is a client error, not a
     * server fault: answer 400 instead of letting it fall through to the 500
     * catch-all, and log at WARN without the multi-page stack trace.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Request body is malformed or has a field in an unexpected format.");
    }

    /**
     * A path/query parameter that fails type conversion — e.g. a non-UUID
     * string in a {@code {id}} segment ("Invalid UUID string: invitations").
     * Same reasoning as {@link #handleUnreadableBody}: client error, 400.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch for '{}': {}", ex.getName(), ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'.");
    }

    @ExceptionHandler(AIServiceException.class)
    public ProblemDetail handleAIServiceException(AIServiceException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(EmailDeliveryException.class)
    public ProblemDetail handleEmailDeliveryException(EmailDeliveryException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ProblemDetail handleRateLimitExceeded(RateLimitExceededException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return problem(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    @ExceptionHandler(PremiumRequiredException.class)
    public ProblemDetail handlePremiumRequired(PremiumRequiredException ex) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN,
                "This feature requires a Premium subscription. Upgrade your organization to access "
                        + ex.getFeature() + ".");
        problem.setProperty("error", "premium_required");
        problem.setProperty("feature", ex.getFeature());
        return problem;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        // Catches both AccessDeniedException (service-layer SecurityUtils.requireOrgAccess)
        // and AuthorizationDeniedException (@PreAuthorize method security), which extends it.
        // Use a generic message so we don't leak the failing authority/expression.
        log.warn("Access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
