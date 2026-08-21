package com.bvisionry.common.exception;

import java.util.Map;

/**
 * A structural-validation failure expressed as per-field errors, for service
 * layer rules bean validation cannot state (weights summing to 100, band
 * contiguity, …). Rendered by {@link GlobalExceptionHandler} in the SAME shape
 * as {@code MethodArgumentNotValidException} — a 400 with a {@code fieldErrors}
 * property — so the web app has one inline-error path for both.
 */
public class FieldValidationException extends RuntimeException {

    private final transient Map<String, String> fieldErrors;

    public FieldValidationException(Map<String, String> fieldErrors) {
        super("Validation failed for " + fieldErrors.size() + " field(s).");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
